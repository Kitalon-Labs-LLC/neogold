#!/usr/bin/env python3
"""breaker/env/seed.py — Synthetic data seeder for the disposable breaker stack (P27).

Creates synthetic-only study data:
- 1 Study (`SKP-BRK`, `ACTIVE`, `DAY_PLUS_OFFSETS`, `Asia/Dhaka`)
- 2 Test sites (`SKP01`, `SKP02` with `is_test=True`)
- Devices with freshly generated EC P-256 keys
- Signed synthetic card batch (`NG-BRK-0001`)
- Synthetic participants (`SKP-0001`..`SKP-0005`) and sessions

Always validates the target URL with `breaker/env/guard.sh` before touching any database or API.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
BACKEND_DIR = REPO_ROOT / "backend"
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))


def _resolve_bash_bin() -> str:
    if os.name == "nt":
        for candidate in (
            r"C:\Program Files\Git\bin\bash.exe",
            r"C:\Program Files\Git\usr\bin\bash.exe",
        ):
            if Path(candidate).exists():
                return candidate
    return "bash"


def run_guard(api_url: str, serial: str | None = None) -> None:
    guard_script = SCRIPT_DIR / "guard.sh"
    cmd = [_resolve_bash_bin(), guard_script.as_posix(), "--api-url", api_url]
    if serial:
        cmd.extend(["--serial", serial])
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"breaker/env/guard.sh refused execution: {proc.stderr.strip()}")


def base64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def generate_p256_device_key() -> tuple[ec.EllipticCurvePrivateKey, dict[str, str], str]:
    privkey = ec.generate_private_key(ec.SECP256R1())
    pub_numbers = privkey.public_key().public_numbers()
    x = base64url_encode(pub_numbers.x.to_bytes(32, "big"))
    y = base64url_encode(pub_numbers.y.to_bytes(32, "big"))
    jwk = {"kty": "EC", "crv": "P-256", "x": x, "y": y}
    pem = privkey.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii")
    return privkey, jwk, pem


def mint_jwt(
    privkey: ec.EllipticCurvePrivateKey,
    device_id: str | UUID,
    method: str,
    path: str,
    body_bytes: bytes = b"",
    public_base_url: str = "http://127.0.0.1:18000/v1",
    iat: int | None = None,
    exp: int | None = None,
    jti: str | None = None,
) -> str:
    now = int(datetime.datetime.now(datetime.UTC).timestamp())
    if iat is None:
        iat = now
    if exp is None:
        exp = iat + 120
    if jti is None:
        jti = str(uuid4())
    clean_path = path.removeprefix("/v1")
    base = public_base_url.rstrip("/")
    htu = f"{base}{clean_path}" if base.endswith("/v1") else f"{base}/v1{clean_path}"
    bsh = base64url_encode(hashlib.sha256(body_bytes).digest())

    header = {"alg": "ES256", "typ": "ng-device+jwt", "kid": str(device_id)}
    claims = {
        "iss": str(device_id),
        "aud": base,
        "iat": iat,
        "exp": exp,
        "jti": jti,
        "htm": method.upper(),
        "htu": htu,
        "bsh": bsh,
    }
    hdr_b64 = base64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = base64url_encode(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{hdr_b64}.{payload_b64}".encode("ascii")
    der_sig = privkey.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der_sig)
    sig_b64 = base64url_encode(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
    return f"{hdr_b64}.{payload_b64}.{sig_b64}"


def generate_synthetic_seed_bundle(
    num_devices: int = 2,
    num_participants: int = 5,
    sessions_per_participant: int = 2,
) -> dict[str, Any]:
    """Builds a complete in-memory synthetic dataset dictionary with freshly generated keys."""
    study_id = str(uuid4())
    sites = [
        {
            "id": str(uuid4()),
            "study_id": study_id,
            "site_code": "SKP01",
            "name": "Breaker Synthetic Site 01 (Test Only)",
            "is_test": True,
        },
        {
            "id": str(uuid4()),
            "study_id": study_id,
            "site_code": "SKP02",
            "name": "Breaker Synthetic Site 02 (Test Only)",
            "is_test": True,
        },
    ]

    devices = []
    for idx in range(num_devices):
        _priv, jwk, pem = generate_p256_device_key()
        canonical_jwk = json.dumps(
            {"crv": jwk["crv"], "kty": jwk["kty"], "x": jwk["x"], "y": jwk["y"]},
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        thumbprint = base64url_encode(hashlib.sha256(canonical_jwk).digest())
        devices.append(
            {
                "id": str(uuid4()),
                "site_id": sites[idx % len(sites)]["id"],
                "site_code": sites[idx % len(sites)]["site_code"],
                "public_key": jwk,
                "private_key_pem": pem,
                "key_thumbprint": thumbprint,
                "key_algorithm": "ES256",
                "manufacturer": "SyntheticTranssion",
                "device_model": f"TECNO-BRK-{idx + 1}",
                "android_api": 34,
                "camera_hardware_level": "LIMITED",
                "qualification": "QUALIFIED",
                "app_version": "1.0.0",
            }
        )

    participants = []
    sessions = []
    for p_idx in range(1, num_participants + 1):
        p_id = str(uuid4())
        p_code = f"SKP-{p_idx:04d}"
        c_form = f"SKP-C-{p_idx:04d}"
        site = sites[0]
        dev = devices[0]
        participants.append(
            {
                "id": p_id,
                "site_id": site["id"],
                "participant_code": p_code,
                "consent_form_id": c_form,
                "consent_date": "2026-10-30",
            }
        )
        for s_idx in range(sessions_per_participant):
            s_id = str(uuid4())
            cap_id = str(uuid4())
            sessions.append(
                {
                    "id": s_id,
                    "participant_id": p_id,
                    "participant_code": p_code,
                    "device_id": dev["id"],
                    "card_status": "UNVERIFIED",
                    "card_batch_id": None,
                    "capture_date": "2026-10-31",
                    "age_hours": 48.0 + s_idx * 12.0,
                    "gestational_age_weeks": 38,
                    "flag_jaundice_under_24h": False,
                    "flag_danger_signs": False,
                    "flag_prior_phototherapy": False,
                    "app_version": "1.0.0",
                    "model_version": "1.0.0",
                    "config_version": "2026.10.0-draft",
                    "captures": [
                        {
                            "capture_id": cap_id,
                            "attempt_number": 1,
                            "site": "STERNUM",
                            "quality_status": "PASSED",
                            "has_image": False,
                            "image_sha256": None,
                            "size_bytes": None,
                        }
                    ],
                }
            )

    return {
        "synthetic_only": True,
        "study": {
            "id": study_id,
            "study_code": "SKP-BRK",
            "name": "NeoGold Breaker Disposable Study",
            "partner_institution": "Kitalon Labs Synthetic QA",
            "ethics_reference": "SYNTH-BRK-2026",
            "timestamp_precision": "DAY_PLUS_OFFSETS",
            "retention_until": "2029-12-31",
            "time_zone": "Asia/Dhaka",
            "min_app_version": "1.0.0",
            "wifi_only": True,
            "status": "ACTIVE",
        },
        "sites": sites,
        "devices": devices,
        "participants": participants,
        "sessions": sessions,
    }


def seed_database(db_session, bundle: dict[str, Any], enrollment_secret: str) -> dict[str, Any]:
    """Inserts the synthetic bundle into a SQLAlchemy session (`Study`, `Site`, `Device`, `Participant`, `Session`)."""
    from app.models.db import Capture, Device, EnrollmentCode, Participant, Session, Site, Study

    study_info = bundle["study"]
    study = db_session.query(Study).filter_by(study_code=study_info["study_code"]).first()
    if not study:
        study = Study(
            id=UUID(study_info["id"]),
            study_code=study_info["study_code"],
            name=study_info["name"],
            partner_institution=study_info["partner_institution"],
            ethics_reference=study_info["ethics_reference"],
            timestamp_precision=study_info["timestamp_precision"],
            retention_until=datetime.date.fromisoformat(study_info["retention_until"]),
            time_zone=study_info["time_zone"],
            min_app_version=study_info["min_app_version"],
            wifi_only=study_info["wifi_only"],
            status=study_info["status"],
        )
        db_session.add(study)
        db_session.flush()
    else:
        study_info["id"] = str(study.id)

    for site_info in bundle["sites"]:
        assert site_info["is_test"] is True, "Breaker seed sites must have is_test=True"
        existing_site = (
            db_session.query(Site)
            .filter_by(study_id=study.id, site_code=site_info["site_code"])
            .first()
        )
        if not existing_site:
            existing_site = Site(
                id=UUID(site_info["id"]),
                study_id=study.id,
                site_code=site_info["site_code"],
                name=site_info["name"],
                is_test=True,
            )
            db_session.add(existing_site)
            db_session.flush()
        else:
            site_info["id"] = str(existing_site.id)

    for dev_info in bundle["devices"]:
        dev = Device(
            id=UUID(dev_info["id"]),
            site_id=UUID(dev_info["site_id"]),
            public_key=dev_info["public_key"],
            key_thumbprint=dev_info["key_thumbprint"],
            key_algorithm=dev_info["key_algorithm"],
            attestation="NONE",
            manufacturer=dev_info["manufacturer"],
            device_model=dev_info["device_model"],
            android_api=dev_info["android_api"],
            camera_hardware_level=dev_info["camera_hardware_level"],
            qualification=dev_info["qualification"],
            app_version=dev_info["app_version"],
        )
        db_session.add(dev)

    # Also seed one unused enrollment code for enrollment attacks
    unused_code = f"BRK0-{uuid4().hex[:4].upper()}-{uuid4().hex[:4].upper()}"
    code_hash = hmac.new(
        enrollment_secret.encode("utf-8"),
        unused_code.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    now = datetime.datetime.now(datetime.UTC)
    db_session.add(
        EnrollmentCode(
            id=uuid4(),
            study_id=study.id,
            site_id=UUID(bundle["sites"][0]["id"]),
            code_hash=code_hash,
            expires_at=now + datetime.timedelta(hours=72),
            created_at=now,
        )
    )
    bundle["unused_enrollment_code"] = unused_code

    for p_info in bundle["participants"]:
        p = Participant(
            id=UUID(p_info["id"]),
            site_id=UUID(p_info["site_id"]),
            participant_code=p_info["participant_code"],
            consent_form_id=p_info["consent_form_id"],
            consent_date=datetime.date.fromisoformat(p_info["consent_date"]),
        )
        db_session.add(p)

    for s_info in bundle["sessions"]:
        manifest_sha = hashlib.sha256(json.dumps(s_info, sort_keys=True).encode("utf-8")).hexdigest()
        s = Session(
            id=UUID(s_info["id"]),
            participant_id=UUID(s_info["participant_id"]),
            device_id=UUID(s_info["device_id"]),
            card_status=s_info["card_status"],
            card_batch_id=s_info["card_batch_id"],
            capture_date=datetime.date.fromisoformat(s_info["capture_date"]),
            age_hours=s_info["age_hours"],
            gestational_age_weeks=s_info["gestational_age_weeks"],
            flag_jaundice_under_24h=s_info["flag_jaundice_under_24h"],
            flag_danger_signs=s_info["flag_danger_signs"],
            flag_prior_phototherapy=s_info["flag_prior_phototherapy"],
            app_version=s_info["app_version"],
            model_version=s_info["model_version"],
            config_version=s_info["config_version"],
            manifest_sha256=manifest_sha,
            status="COMPLETE",
            completed_on=datetime.date.fromisoformat(s_info["capture_date"]),
        )
        db_session.add(s)
        db_session.flush()
        for c_info in s_info["captures"]:
            db_session.add(
                Capture(
                    id=UUID(c_info["capture_id"]),
                    session_id=s.id,
                    attempt_number=c_info["attempt_number"],
                    site=c_info["site"],
                    capture_metadata={"iso": 100, "exposure_ms": 10},
                    quality_status=c_info["quality_status"],
                    has_image=c_info["has_image"],
                    sha256=c_info["image_sha256"],
                    size_bytes=c_info["size_bytes"],
                    verified=False,
                )
            )

    db_session.commit()
    return bundle


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Seed synthetic study data into the breaker stack.")
    parser.add_argument("--api-url", default=os.getenv("BREAKER_API_URL", "http://127.0.0.1:18000/v1"))
    parser.add_argument("--db-url", default=os.getenv("BREAKER_DATABASE_URL", ""))
    parser.add_argument(
        "--out",
        "--output",
        dest="out",
        default=str(SCRIPT_DIR / ".runtime" / "seed-state.json"),
    )
    parser.add_argument("--dry-run", action="store_true", help="Generate bundle JSON without connecting to Postgres.")
    args = parser.parse_args(argv)

    run_guard(args.api_url)
    bundle = generate_synthetic_seed_bundle()

    if not args.dry_run and args.db_url:
        enrollment_secret = os.getenv(
            "BREAKER_ENROLLMENT_SECRET",
            "breaker-synthetic-only-0123456789abcdef0123456789abcdef",
        )
        os.environ.setdefault("ENROLLMENT_CODE_SECRET", enrollment_secret)
        from app.database import SessionLocal, configure_engine

        configure_engine(args.db_url)
        with SessionLocal() as session:
            seed_database(session, bundle, enrollment_secret)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(bundle, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
