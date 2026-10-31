from __future__ import annotations

import base64
import datetime
import json
import logging
import re
from uuid import UUID

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session
from starlette.responses import JSONResponse

from app.config import settings
from app.database import SessionLocal
from app.errors import (
    AppVersionUnsupportedError,
    AuthClaimMismatchError,
    AuthExpiredError,
    AuthInvalidSignatureError,
    AuthMissingError,
    AuthReplayError,
    CaptureNotInManifestError,
    ClockSkewError,
    DeviceRevokedError,
    DeviceUnknownError,
    HashMismatchError,
    IdempotencyKeyReusedError,
    NeoGoldError,
    PayloadTooLargeError,
    RequestInProgressError,
    SessionUnknownError,
    StudyClosedError,
    StudyPausedError,
    UnsupportedMediaTypeError,
    ValidationFailedError,
    problem_response,
)
from app.middleware.rate_limit import device_limiter, enrollment_limiter, get_client_ip
from app.models.db import (
    AuthNonce,
    Capture,
    Device,
    IdempotencyRecord,
    Site,
    Study,
)
from app.models.db import (
    Session as DbSession,
)
from app.security.jwk import base64url_decode, compute_bsh, verify_es256_signature
from app.study_time import study_today

logger = logging.getLogger("neogold")

DETERMINISTIC_ERROR_CODES = {
    "VALIDATION_FAILED",
    "CONFLICT_IMMUTABLE",
    "PARTICIPANT_CODE_TAKEN",
    "PAYLOAD_TOO_LARGE",
    "UNSUPPORTED_MEDIA_TYPE",
    "IMAGE_INVALID",
    "IMAGE_METADATA_NOT_ALLOWED",
    "CARD_BATCH_INVALID",
}


def parse_semver(v: str) -> tuple[int, int, int]:
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", v.strip())
    if not m:
        raise ValueError(f"Invalid semver: {v}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def is_exempt_route(method: str, path: str) -> bool:
    """Exempt from study-status and app-version checks:
    GET /device, GET /sessions/{id}, and DELETE /device.
    """
    clean_path = path
    clean_path = clean_path.removeprefix("/v1")

    if method == "GET" and clean_path == "/device":
        return True
    if method == "DELETE" and clean_path == "/device":
        return True
    return bool(method == "GET" and re.match(r"^/sessions/[0-9a-fA-F-]+$", clean_path))


def is_unauthenticated_route(method: str, path: str) -> bool:
    clean_path = path
    clean_path = clean_path.removeprefix("/v1")

    if clean_path == "/health":
        return True
    return bool(clean_path == "/enrollments" and method == "POST")


def is_image_route(method: str, path: str) -> bool:
    clean_path = path
    clean_path = clean_path.removeprefix("/v1")
    return bool(re.match(r"^/sessions/[0-9a-fA-F-]+/captures/[0-9a-fA-F-]+/image$", clean_path))


def normalize_htu(path: str, public_base_url: str) -> str:
    clean_path = path
    clean_path = clean_path.removeprefix("/v1")

    base = public_base_url.rstrip("/")
    if base.endswith("/v1"):
        return f"{base}{clean_path}"
    else:
        return f"{base}/v1{clean_path}"


def parse_digest_header(digest_header: str | None) -> str | None:
    if not digest_header:
        return None
    m = re.match(r"^sha-256=:([A-Za-z0-9+/]{43}=):$", digest_header.strip())
    if not m:
        return None
    b64_val = m.group(1)
    raw = base64.b64decode(b64_val)
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


class DeviceAuthMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        method = scope.get("method", "GET").upper()
        request_id = scope.get("state", {}).get("request_id", "00000000000000000000000000")
        t0 = scope.get("state", {}).get("t0", datetime.datetime.now(datetime.UTC).timestamp())

        headers_dict: dict[str, str] = {}
        for k, v in scope.get("headers", []):
            headers_dict[k.decode("latin1").lower()] = v.decode("latin1")

        # 1. Unauthenticated routes (/health, /enrollments)
        if is_unauthenticated_route(method, path):
            if path.rstrip("/").endswith("/enrollments") and method == "POST":
                client_ip = get_client_ip(scope)
                try:
                    enrollment_limiter.check_ip(client_ip)
                except NeoGoldError as err:
                    resp = problem_response(err, request_id, path)
                    await resp(scope, receive, send)
                    return
            await self.app(scope, receive, send)
            return

        db: Session = SessionLocal()
        idempotency_record_inserted = False
        device_id_val: UUID | None = None
        idempotency_key_val: UUID | None = None
        bsh_val: str | None = None

        try:
            # Phase A: Headers only
            auth_header = headers_dict.get("authorization")
            if not auth_header:
                raise AuthMissingError("Authorization header missing")

            parts = auth_header.split(" ")
            if len(parts) != 2 or parts[0] != "Bearer":
                raise AuthInvalidSignatureError("Malformed Authorization header")

            jwt_token = parts[1]
            jwt_parts = jwt_token.split(".")
            if len(jwt_parts) != 3:
                raise AuthInvalidSignatureError("Malformed JWT structure")

            # Step 1: Decode header JSON
            try:
                header_json_bytes = base64url_decode(jwt_parts[0])
                header = json.loads(header_json_bytes)
            except Exception as e:
                raise AuthInvalidSignatureError("Invalid JOSE header encoding") from e

            if header.get("alg") != "ES256" or header.get("typ") != "ng-device+jwt":
                raise AuthInvalidSignatureError("Invalid alg or typ in header")

            kid_str = header.get("kid")
            if not kid_str:
                raise AuthInvalidSignatureError("Missing kid in header")

            try:
                device_uuid = UUID(kid_str)
            except Exception as e:
                raise DeviceUnknownError("Invalid kid UUID") from e

            # Step 2: kid names an enrolled device
            device = db.execute(select(Device).where(Device.id == device_uuid)).scalar_one_or_none()
            if not device:
                raise DeviceUnknownError("Device not found")
            device_id_val = device.id

            # Step 3: Signature verifies with that device's stored key
            signing_input = f"{jwt_parts[0]}.{jwt_parts[1]}".encode("ascii")
            try:
                sig_bytes = base64url_decode(jwt_parts[2])
            except Exception as e:
                raise AuthInvalidSignatureError("Invalid signature base64") from e

            if not verify_es256_signature(device.public_key, signing_input, sig_bytes):
                raise AuthInvalidSignatureError("Signature verification failed")

            # Step 4: All eight claims present, iss == kid, aud == PUBLIC_BASE_URL, exp - iat <= 300
            try:
                payload_json_bytes = base64url_decode(jwt_parts[1])
                claims = json.loads(payload_json_bytes)
            except Exception as e:
                raise AuthClaimMismatchError("Invalid claims JSON") from e

            required_claims = ["iss", "aud", "iat", "exp", "jti", "htm", "htu", "bsh"]
            for claim in required_claims:
                if claim not in claims:
                    raise AuthClaimMismatchError(f"Missing claim: {claim}")

            if claims["iss"] != kid_str:
                raise AuthClaimMismatchError("iss does not match kid")

            configured_aud = settings.PUBLIC_BASE_URL.rstrip("/")
            if claims["aud"].rstrip("/") != configured_aud:
                raise AuthClaimMismatchError("aud does not match PUBLIC_BASE_URL")

            iat = claims["iat"]
            exp = claims["exp"]
            if not isinstance(iat, (int, float)) or not isinstance(exp, (int, float)):
                raise AuthClaimMismatchError("iat and exp must be numeric")

            if exp - iat > 300:
                raise AuthClaimMismatchError("Token life exceeds 300 seconds")

            # Step 5: Time measured against t0
            server_time_dt = datetime.datetime.now(datetime.UTC)
            if iat > t0 + 60:
                raise ClockSkewError(server_time=server_time_dt)
            if t0 > exp + 30:
                raise AuthExpiredError(server_time=server_time_dt)

            # Step 6: htm and htu match request
            if claims["htm"] != method:
                raise AuthClaimMismatchError("htm does not match request method")

            expected_htu = normalize_htu(path, settings.PUBLIC_BASE_URL)
            if claims["htu"].lower() != expected_htu.lower():
                raise AuthClaimMismatchError("htu does not match request URL")

            bsh_val = claims["bsh"]

            # For image uploads: bsh and Content-Digest describe the same hash
            content_digest_hdr = headers_dict.get("content-digest")
            if is_image_route(method, path):
                digest_b64url = parse_digest_header(content_digest_hdr)
                if not digest_b64url or digest_b64url != bsh_val:
                    raise AuthClaimMismatchError("bsh does not match Content-Digest")

            # Step 7: Device not revoked
            if device.revoked_at is not None:
                raise DeviceRevokedError("Device is revoked")

            # Step 8: Insert jti into auth_nonce with expires_at = exp + 30 seconds
            try:
                jti_uuid = UUID(claims["jti"])
            except Exception as e:
                raise AuthClaimMismatchError("jti must be a valid UUID") from e

            expires_at_dt = datetime.datetime.fromtimestamp(exp + 30, tz=datetime.UTC)
            nonce_exists = db.execute(select(AuthNonce).where(AuthNonce.jti == jti_uuid)).scalar_one_or_none()
            if nonce_exists:
                raise AuthReplayError("Token jti replay detected")

            db.add(AuthNonce(jti=jti_uuid, device_id=device.id, expires_at=expires_at_dt))
            db.commit()

            # Step 9 & 10: Study status and min_app_version (skipped for exempt routes)
            site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()
            study = db.execute(select(Study).where(Study.id == site.study_id)).scalar_one()

            if not is_exempt_route(method, path):
                if study.status == "PAUSED":
                    raise StudyPausedError()
                elif study.status == "CLOSED":
                    raise StudyClosedError()

                app_version_hdr = headers_dict.get("x-neogold-app-version")
                if not app_version_hdr:
                    raise ValidationFailedError("Missing X-NeoGold-App-Version header")

                try:
                    app_v = parse_semver(app_version_hdr)
                    min_v = parse_semver(study.min_app_version)
                    if app_v < min_v:
                        raise AppVersionUnsupportedError(min_app_version=study.min_app_version)
                except ValueError as e:
                    raise ValidationFailedError(str(e)) from e

            # Step 11: Route checks (Phase A)
            content_length_str = headers_dict.get("content-length")
            content_length = int(content_length_str) if content_length_str and content_length_str.isdigit() else 0

            max_allowed_bytes = 20000000 if is_image_route(method, path) else 262144
            if content_length > max_allowed_bytes:
                raise PayloadTooLargeError("Payload exceeds size limit")

            if is_image_route(method, path):
                content_type = headers_dict.get("content-type", "")
                if content_type.lower() != "image/jpeg":
                    raise UnsupportedMediaTypeError("Content-Type must be image/jpeg")

                clean_path = path
                clean_path = clean_path.removeprefix("/v1")
                m_img = re.match(r"^/sessions/([0-9a-fA-F-]+)/captures/([0-9a-fA-F-]+)/image$", clean_path)
                if m_img:
                    s_id = UUID(m_img.group(1))
                    c_id = UUID(m_img.group(2))
                    session_row = db.execute(select(DbSession).where(DbSession.id == s_id)).scalar_one_or_none()
                    if not session_row:
                        raise SessionUnknownError("Session not found", status=404)
                    capture_row = db.execute(
                        select(Capture).where(Capture.session_id == s_id, Capture.id == c_id)
                    ).scalar_one_or_none()
                    if not capture_row or not capture_row.has_image:
                        raise CaptureNotInManifestError("Capture not found in manifest or has_image false")
                    # Declared digest matches manifest sha256
                    digest_b64url = parse_digest_header(content_digest_hdr)
                    if digest_b64url:
                        digest_hex = base64url_decode(digest_b64url).hex()
                        if digest_hex != capture_row.sha256:
                            raise HashMismatchError("Declared digest does not match manifest image_sha256")

            # Step 12: Rate limit and Idempotency
            device_limiter.check_and_consume(str(device.id))

            is_write_op = method in ("PUT", "DELETE", "POST")
            idempotency_key_hdr = headers_dict.get("idempotency-key")

            if is_write_op:
                if not idempotency_key_hdr:
                    raise ValidationFailedError("Missing Idempotency-Key header")
                try:
                    idempotency_key_val = UUID(idempotency_key_hdr)
                except Exception as e:
                    raise ValidationFailedError("Invalid Idempotency-Key format") from e

                # Request hash: for image routes, the declared digest; for JSON, bsh
                request_hash = content_digest_hdr if is_image_route(method, path) else bsh_val
                if request_hash is None:
                    request_hash = ""

                # Check idempotency record
                idem_rec = db.execute(
                    select(IdempotencyRecord).where(
                        IdempotencyRecord.device_id == device.id,
                        IdempotencyRecord.idempotency_key == idempotency_key_val,
                    )
                ).scalar_one_or_none()

                if idem_rec:
                    if idem_rec.state == "DONE":
                        if idem_rec.request_sha256 == request_hash:
                            # Replay stored response
                            status_code = idem_rec.response_status or 200
                            content_body = idem_rec.response_body
                            headers = {}
                            if status_code in (400, 401, 403, 404, 409, 413, 415, 422, 426, 429, 500, 503, 507):
                                headers["Content-Type"] = "application/problem+json"
                            resp = JSONResponse(status_code=status_code, content=content_body, headers=headers)
                            await resp(scope, receive, send)
                            return
                        else:
                            raise IdempotencyKeyReusedError()
                    elif idem_rec.state == "IN_PROGRESS":
                        raise RequestInProgressError()

                # Insert IN_PROGRESS row
                db.add(
                    IdempotencyRecord(
                        device_id=device.id,
                        idempotency_key=idempotency_key_val,
                        request_sha256=request_hash,
                        state="IN_PROGRESS",
                    )
                )
                db.commit()
                idempotency_record_inserted = True

            # Phase B: Read body & verify hash
            body_chunks = []
            total_body_len = 0
            while True:
                message = await receive()
                if message["type"] == "http.request":
                    chunk = message.get("body", b"")
                    total_body_len += len(chunk)
                    if total_body_len > max_allowed_bytes:
                        raise PayloadTooLargeError("Payload exceeds size limit")
                    body_chunks.append(chunk)
                    if not message.get("more_body", False):
                        break

            body_bytes = b"".join(body_chunks)

            # JSON hash verification: body hash must equal bsh
            if not is_image_route(method, path):
                actual_bsh = compute_bsh(body_bytes)
                if actual_bsh != bsh_val:
                    raise AuthClaimMismatchError("Body hash does not match bsh claim")

            # Replay receive callable for downstream handlers
            body_replayed = False

            async def custom_receive():
                nonlocal body_replayed
                if not body_replayed:
                    body_replayed = True
                    return {"type": "http.request", "body": body_bytes, "more_body": False}
                return {"type": "http.request", "body": b"", "more_body": False}

            # Capture response status and body for idempotency
            captured_status: int = 200
            captured_headers: list[tuple[bytes, bytes]] = []
            captured_body_chunks: list[bytes] = []

            async def custom_send(message):
                nonlocal captured_status, captured_headers, captured_body_chunks
                if message["type"] == "http.response.start":
                    captured_status = message.get("status", 200)
                    captured_headers = list(message.get("headers", []))
                    await send(message)
                elif message["type"] == "http.response.body":
                    captured_body_chunks.append(message.get("body", b""))
                    await send(message)
                else:
                    await send(message)

            # Run downstream app
            # Attach device and db to scope state
            scope["state"]["device_id"] = device.id
            scope["state"]["device"] = device
            scope["state"]["site"] = site
            scope["state"]["study"] = study

            await self.app(scope, custom_receive, custom_send)

            # Post-processing: Idempotency update and last_seen_on
            full_response_body = b"".join(captured_body_chunks)
            parsed_response_json = None
            if full_response_body:
                try:
                    parsed_response_json = json.loads(full_response_body)
                except Exception:
                    parsed_response_json = None

            if idempotency_record_inserted and idempotency_key_val and device_id_val:
                is_success = 200 <= captured_status < 300
                is_deterministic_err = False
                if parsed_response_json and isinstance(parsed_response_json, dict):
                    err_code = parsed_response_json.get("code")
                    if err_code in DETERMINISTIC_ERROR_CODES:
                        is_deterministic_err = True

                if is_success or is_deterministic_err:
                    db.execute(
                        update(IdempotencyRecord)
                        .where(
                            IdempotencyRecord.device_id == device_id_val,
                            IdempotencyRecord.idempotency_key == idempotency_key_val,
                        )
                        .values(
                            state="DONE",
                            response_status=captured_status,
                            response_body=parsed_response_json,
                        )
                    )
                    db.commit()
                else:
                    # Transient error: delete IN_PROGRESS row
                    db.execute(
                        delete(IdempotencyRecord).where(
                            IdempotencyRecord.device_id == device_id_val,
                            IdempotencyRecord.idempotency_key == idempotency_key_val,
                        )
                    )
                    db.commit()

            # Update last_seen_on on success
            if 200 <= captured_status < 300:
                zone = db.execute(
                    select(Study.time_zone).join(Site, Site.study_id == Study.id).where(Site.id == device.site_id)
                ).scalar_one()
                today = study_today(zone)
                db.execute(
                    update(Device)
                    .where(Device.id == device.id, Device.last_seen_on.is_distinct_from(today))
                    .values(last_seen_on=today)
                )
                db.commit()

        except NeoGoldError as err:
            # If idempotency record was inserted, clean it up or record deterministic outcome
            if idempotency_record_inserted and idempotency_key_val and device_id_val:
                try:
                    if err.code in DETERMINISTIC_ERROR_CODES:
                        err_payload = problem_response(err, request_id, path)
                        db.execute(
                            update(IdempotencyRecord)
                            .where(
                                IdempotencyRecord.device_id == device_id_val,
                                IdempotencyRecord.idempotency_key == idempotency_key_val,
                            )
                            .values(
                                state="DONE",
                                response_status=err.status,
                                response_body=json.loads(err_payload.body.decode("utf-8")),
                            )
                        )
                        db.commit()
                    else:
                        db.execute(
                            delete(IdempotencyRecord).where(
                                IdempotencyRecord.device_id == device_id_val,
                                IdempotencyRecord.idempotency_key == idempotency_key_val,
                            )
                        )
                        db.commit()
                except Exception as cleanup_err:
                    logger.debug("Idempotency cleanup error: %s", cleanup_err)

            resp = problem_response(err, request_id, path)
            await resp(scope, receive, send)
            return
        finally:
            db.close()
