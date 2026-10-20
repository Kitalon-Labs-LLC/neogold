from __future__ import annotations

import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Path, Request, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import (
    ConflictImmutableError,
    ParticipantUnknownError,
    Problem,
    SessionUnknownError,
    ValidationFailedError,
)
from app.models.api import TsbReceipt, TsbUpload
from app.models.db import (
    Device,
    Participant,
    ParticipantAlias,
    Site,
    Study,
    TsbMeasurement,
)
from app.models.db import (
    Session as DbSession,
)

router = APIRouter(tags=["Measurements"])


@router.put(
    "/tsb-measurements/{tsb_id}",
    operation_id="putTsbMeasurement",
    summary="Submit a blood bilirubin (TSB) value paired with a participant and optionally a session.",
    response_model=TsbReceipt,
    responses={
        200: {"model": TsbReceipt, "description": "Identical measurement already stored."},
        201: {"model": TsbReceipt, "description": "Measurement stored."},
        400: {"model": Problem, "description": "Malformed request or failed validation."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        409: {"model": Problem, "description": "Conflicts with existing data."},
        422: {"model": Problem, "description": "Well-formed but rejected."},
        426: {"model": Problem, "description": "App version below server minimum."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def put_tsb_measurement(
    request: Request,
    response: Response,
    body: TsbUpload,
    tsb_id: UUID = Path(...),
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> TsbReceipt:
    device_id: UUID = request.scope["state"]["device_id"]
    device = db.execute(select(Device).where(Device.id == device_id)).scalar_one()
    site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()
    study = db.execute(select(Study).where(Study.id == site.study_id)).scalar_one()

    today = datetime.datetime.now(datetime.UTC).date()

    # 1. Resolve participant
    canonical_participant = None
    direct_p = db.execute(select(Participant).where(Participant.id == body.participant_id)).scalar_one_or_none()
    if direct_p:
        canonical_participant = direct_p
    else:
        alias_p = db.execute(
            select(ParticipantAlias).where(ParticipantAlias.alias_id == body.participant_id)
        ).scalar_one_or_none()
        if alias_p:
            canonical_participant = db.execute(
                select(Participant).where(Participant.id == alias_p.participant_id)
            ).scalar_one_or_none()

    if not canonical_participant or canonical_participant.site_id != site.id:
        raise ParticipantUnknownError(f"Participant {body.participant_id} not found at this site")

    # 2. If session_id provided, verify it exists and belongs to the same participant
    if body.session_id is not None:
        session_row = db.execute(select(DbSession).where(DbSession.id == body.session_id)).scalar_one_or_none()
        if not session_row:
            raise SessionUnknownError(f"Session {body.session_id} not found")
        if session_row.participant_id != canonical_participant.id:
            raise ValidationFailedError("Session does not belong to the same participant")

    # 3. Timestamp precision check
    if study.timestamp_precision != "FULL" and body.draw_time is not None:
        raise ValidationFailedError("draw_time not allowed when study timestamp_precision is not FULL")

    # 4. Check existing TSB measurement
    existing = db.execute(select(TsbMeasurement).where(TsbMeasurement.id == tsb_id)).scalar_one_or_none()
    if existing:
        if (
            existing.session_id is None
            and body.session_id is not None
            and existing.participant_id == canonical_participant.id
            and float(existing.tsb_mg_dl) == float(body.tsb_mg_dl)
            and existing.method == body.method
        ):
            # One allowed linking update
            existing.session_id = body.session_id
            existing.minutes_from_capture = body.minutes_from_capture
            existing.linked_on = today
            db.commit()
            response.status_code = status.HTTP_200_OK
            return TsbReceipt(
                tsb_id=tsb_id,
                participant_id=canonical_participant.id,
                linked=True,
                received_on=existing.received_on,
            )
        elif (
            existing.participant_id == canonical_participant.id
            and existing.session_id == body.session_id
            and float(existing.tsb_mg_dl) == float(body.tsb_mg_dl)
            and existing.minutes_from_capture == body.minutes_from_capture
            and existing.method == body.method
        ):
            response.status_code = status.HTTP_200_OK
            return TsbReceipt(
                tsb_id=tsb_id,
                participant_id=canonical_participant.id,
                linked=existing.session_id is not None,
                received_on=existing.received_on,
            )
        else:
            raise ConflictImmutableError(f"TSB measurement {tsb_id} already exists with different content")

    # 5. Insert new measurement
    new_tsb = TsbMeasurement(
        id=tsb_id,
        participant_id=canonical_participant.id,
        session_id=body.session_id,
        tsb_mg_dl=body.tsb_mg_dl,
        minutes_from_capture=body.minutes_from_capture,
        draw_time=body.draw_time,
        method=body.method,
        received_on=today,
    )
    db.add(new_tsb)
    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return TsbReceipt(
        tsb_id=tsb_id,
        participant_id=canonical_participant.id,
        linked=body.session_id is not None,
        received_on=today,
    )
