from __future__ import annotations

import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request, status
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.api import DeviceStatus, Problem, UploadPolicy
from app.models.db import Device, Site, Study

router = APIRouter(tags=["Device"])


@router.get(
    "/device",
    operation_id="getDevice",
    summary="Current device status, study status, and upload policy.",
    response_model=DeviceStatus,
    status_code=status.HTTP_200_OK,
    responses={
        200: {"model": DeviceStatus, "description": "Device status."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def get_device(
    request: Request,
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> DeviceStatus:
    device_id: UUID = request.scope["state"]["device_id"]
    device = db.execute(select(Device).where(Device.id == device_id)).scalar_one()
    site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()
    study = db.execute(select(Study).where(Study.id == site.study_id)).scalar_one()

    now = datetime.datetime.now(datetime.UTC)
    return DeviceStatus(
        data_generation=study.data_generation,
        device_id=device.id,
        site_code=site.site_code,
        study_status=study.status,
        revoked=device.revoked_at is not None,
        server_time=now,
        min_app_version=study.min_app_version,
        upload_policy=UploadPolicy(
            wifi_only=study.wifi_only,
            max_image_bytes=study.max_image_bytes,
            max_requests_per_minute=study.max_requests_per_minute,
            timestamp_precision=study.timestamp_precision,
            time_zone=study.time_zone,
        ),
    )


@router.delete(
    "/device",
    operation_id="leaveStudy",
    summary="Self-revoke this device (Leave study). The key stops working immediately.",
    status_code=status.HTTP_204_NO_CONTENT,
    responses={
        204: {"description": "Device revoked."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def leave_study(
    request: Request,
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> None:
    device_id: UUID = request.scope["state"]["device_id"]
    now = datetime.datetime.now(datetime.UTC)
    db.execute(
        update(Device)
        .where(Device.id == device_id)
        .values(revoked_at=now, revoked_reason="SELF_UNENROLLED")
    )
    db.commit()
