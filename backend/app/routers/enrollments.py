from __future__ import annotations

import datetime
import hashlib
import hmac
import re
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Request, status
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.errors import (
    AppVersionUnsupportedError,
    DeviceKeyAlreadyEnrolledError,
    EnrollmentCodeExpiredError,
    EnrollmentCodeInvalidError,
    EnrollmentCodeUsedError,
    Problem,
    ValidationFailedError,
)
from app.middleware.rate_limit import enrollment_limiter, get_client_ip
from app.models.api import EnrollmentRequest, EnrollmentResponse, UploadPolicy
from app.models.db import Device, EnrollmentCode, Site, Study
from app.security.attestation import verify_attestation
from app.security.jwk import compute_jwk_thumbprint

router = APIRouter(tags=["Enrollment"])


def parse_semver(v: str) -> tuple[int, int, int]:
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", v.strip())
    if not m:
        raise ValueError(f"Invalid semver: {v}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


@router.post(
    "/enrollments",
    operation_id="enrollDevice",
    summary="Enroll a phone with a one-time code and register its public key.",
    response_model=EnrollmentResponse,
    status_code=status.HTTP_201_CREATED,
    responses={
        201: {"model": EnrollmentResponse, "description": "Phone enrolled."},
        400: {"model": Problem, "description": "Malformed request or failed validation."},
        403: {"model": Problem, "description": "Enrollment code invalid, expired, or used."},
        409: {"model": Problem, "description": "Conflicts with existing data."},
        426: {"model": Problem, "description": "App version below server minimum."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def enroll_device(
    request: Request,
    body: EnrollmentRequest,
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> EnrollmentResponse:
    client_ip = get_client_ip(request.scope)
    enrollment_limiter.check_ip(client_ip)

    # 1. Compute code HMAC
    code_raw = body.enrollment_code.strip()
    code_hash = hmac.new(
        settings.enrollment_code_key(),
        code_raw.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    # 2. Look up code
    code_row = db.execute(select(EnrollmentCode).where(EnrollmentCode.code_hash == code_hash)).scalar_one_or_none()
    if not code_row:
        enrollment_limiter.record_failure(client_ip)
        raise EnrollmentCodeInvalidError("Enrollment code not recognised")

    thumbprint = compute_jwk_thumbprint(body.device_public_key.model_dump())

    # 3. Check if already used
    if code_row.used_at is not None:
        if (
            code_row.enroll_idempotency_key == idempotency_key
            and code_row.enroll_key_thumbprint == thumbprint
            and code_row.device_id is not None
        ):
            # Lost 201 replay: return original device and response!
            device = db.execute(select(Device).where(Device.id == code_row.device_id)).scalar_one()
            site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()
            study = db.execute(select(Study).where(Study.id == site.study_id)).scalar_one()
            now = datetime.datetime.now(datetime.UTC)
            return EnrollmentResponse(
                data_generation=study.data_generation,
                device_id=device.id,
                study_code=study.study_code,
                study_name=study.name,
                site_code=site.site_code,
                server_time=now,
                upload_policy=UploadPolicy(
                    wifi_only=study.wifi_only,
                    max_image_bytes=study.max_image_bytes,
                    max_requests_per_minute=study.max_requests_per_minute,
                    timestamp_precision=study.timestamp_precision,
                    time_zone=study.time_zone,
                ),
            )
        else:
            enrollment_limiter.record_failure(client_ip)
            raise EnrollmentCodeUsedError("Enrollment code already used")

    # 4. Check if expired
    now = datetime.datetime.now(datetime.UTC)
    if code_row.expires_at <= now:
        enrollment_limiter.record_failure(client_ip)
        raise EnrollmentCodeExpiredError("Enrollment code has expired")

    # 5. Check app version vs study minimum
    study = db.execute(select(Study).where(Study.id == code_row.study_id)).scalar_one()
    try:
        app_v = parse_semver(x_neogold_app_version)
        min_v = parse_semver(study.min_app_version)
        if app_v < min_v:
            raise AppVersionUnsupportedError(min_app_version=study.min_app_version)
    except ValueError as e:
        raise ValidationFailedError(str(e)) from e

    # 6. Check if public key is already registered on another device
    existing_dev = db.execute(
        select(Device).where(Device.key_thumbprint == thumbprint)
    ).scalar_one_or_none()
    if existing_dev:
        raise DeviceKeyAlreadyEnrolledError("This public key is already registered")

    # 7. Key attestation verification
    attestation_res = verify_attestation(
        body.key_attestation_chain,
        code_raw,
        body.device_public_key.model_dump(),
    )

    # 8. Atomic code consumption: mark used_at
    update_res = db.execute(
        update(EnrollmentCode)
        .where(
            EnrollmentCode.id == code_row.id,
            EnrollmentCode.used_at.is_(None),
            EnrollmentCode.expires_at > now,
        )
        .values(
            used_at=now,
        )
        .returning(EnrollmentCode.id)
    ).scalar_one_or_none()

    if not update_res:
        # Atomic update failed (concurrent enrollment or expired)
        enrollment_limiter.record_failure(client_ip)
        code_check = db.execute(select(EnrollmentCode).where(EnrollmentCode.id == code_row.id)).scalar_one()
        if code_check.used_at is not None:
            raise EnrollmentCodeUsedError("Enrollment code already used")
        raise EnrollmentCodeExpiredError("Enrollment code has expired")

    # 9. Insert device record
    site = db.execute(select(Site).where(Site.id == code_row.site_id)).scalar_one()
    new_device_id = uuid4()
    new_device = Device(
        id=new_device_id,
        site_id=site.id,
        public_key=body.device_public_key.model_dump(),
        key_thumbprint=thumbprint,
        key_algorithm="ES256",
        attestation=attestation_res,
        manufacturer=body.device.manufacturer,
        device_model=body.device.model,
        android_api=body.device.android_api,
        camera_hardware_level=body.device.camera_hardware_level,
        qualification=body.device.qualification,
        app_version=body.device.app_version,
        enrolled_at=now,
    )
    db.add(new_device)
    db.flush()

    # 10. Update enrollment_code with device_id, enroll_idempotency_key, and enroll_key_thumbprint
    db.execute(
        update(EnrollmentCode)
        .where(EnrollmentCode.id == code_row.id)
        .values(
            device_id=new_device_id,
            enroll_idempotency_key=idempotency_key,
            enroll_key_thumbprint=thumbprint,
        )
    )
    db.commit()


    return EnrollmentResponse(
        data_generation=study.data_generation,
        device_id=new_device.id,
        study_code=study.study_code,
        study_name=study.name,
        site_code=site.site_code,
        server_time=now,
        upload_policy=UploadPolicy(
            wifi_only=study.wifi_only,
            max_image_bytes=study.max_image_bytes,
            max_requests_per_minute=study.max_requests_per_minute,
            timestamp_precision=study.timestamp_precision,
            time_zone=study.time_zone,
        ),
    )
