from __future__ import annotations

import datetime
import hashlib
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Path, Request, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import (
    CardBatchUnknownError,
    ConflictImmutableError,
    NotFoundError,
    ParticipantUnknownError,
    ParticipantWithdrawnError,
    Problem,
    ValidationFailedError,
)
from app.models.api import ImageReceipt, SessionManifest, SessionStatus
from app.models.db import (
    Capture as DbCapture,
)
from app.models.db import (
    CardBatch,
    Device,
    Participant,
    ParticipantAlias,
    Site,
    Study,
)
from app.models.db import (
    Session as DbSession,
)

router = APIRouter(tags=["Sessions"])


@router.put(
    "/sessions/{session_id}",
    operation_id="putSessionManifest",
    summary="Submit the session manifest. Send this before any of the session's images.",
    response_model=SessionStatus,
    responses={
        200: {
            "model": SessionStatus,
            "description": "Identical manifest already accepted; current status returned.",
        },
        201: {
            "model": SessionStatus,
            "description": "Manifest accepted; session is RECEIVING until every image is verified.",
        },
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
def put_session_manifest(
    request: Request,
    response: Response,
    body: SessionManifest,
    session_id: UUID = Path(...),
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> SessionStatus:
    device_id: UUID = request.scope["state"]["device_id"]
    device = db.execute(select(Device).where(Device.id == device_id)).scalar_one()
    site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()
    study = db.execute(select(Study).where(Study.id == site.study_id)).scalar_one()

    today = datetime.datetime.now(datetime.UTC).date()

    # 1. Resolve participant (directly or via alias)
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

    if canonical_participant.withdrawn_on is not None:
        raise ParticipantWithdrawnError("Participant has withdrawn consent")

    if canonical_participant.consent_date > body.capture_date:
        raise ValidationFailedError("consent_date is after capture_date")

    # 2. Card batch checks
    if body.card_status in ("VERIFIED", "EXPIRED"):
        if not body.card_batch_id:
            raise ValidationFailedError("card_batch_id required when card_status is VERIFIED or EXPIRED")
        batch_row = db.execute(select(CardBatch).where(CardBatch.batch_id == body.card_batch_id)).scalar_one_or_none()
        if not batch_row:
            raise CardBatchUnknownError(f"Card batch {body.card_batch_id} not registered")
    else:
        if body.card_batch_id is not None:
            raise ValidationFailedError("card_batch_id must be null when card_status is UNVERIFIED")

    # 3. Timestamp precision check
    if study.timestamp_precision != "FULL" and body.captured_at is not None:
        raise ValidationFailedError("captured_at not allowed when study timestamp_precision is not FULL")

    # 4. Estimate bounds check
    if (
        body.estimate_mg_dl is not None
        and body.estimate_low_mg_dl is not None
        and body.estimate_high_mg_dl is not None
        and not (body.estimate_low_mg_dl <= body.estimate_mg_dl <= body.estimate_high_mg_dl)
    ):
        raise ValidationFailedError("estimate must be between estimate_low and estimate_high")

    # 5. Captures validation
    seen_capture_ids = set()
    seen_site_attempts = set()
    for c in body.captures:
        if c.capture_id in seen_capture_ids:
            raise ValidationFailedError(f"Duplicate capture_id: {c.capture_id}")
        seen_capture_ids.add(c.capture_id)

        site_attempt = (c.site, c.attempt_number)
        if site_attempt in seen_site_attempts:
            raise ValidationFailedError(f"Duplicate attempt {c.attempt_number} for site {c.site}")
        seen_site_attempts.add(site_attempt)

        if not c.has_image:
            if c.image_sha256 is not None or c.size_bytes is not None:
                raise ValidationFailedError("has_image=false requires image_sha256 and size_bytes to be null")
        else:
            if c.image_sha256 is None or c.size_bytes is None:
                raise ValidationFailedError("has_image=true requires image_sha256 and size_bytes")

    manifest_hash = hashlib.sha256(body.model_dump_json().encode("utf-8")).hexdigest()

    # 6. Check existing session
    existing_session = db.execute(select(DbSession).where(DbSession.id == session_id)).scalar_one_or_none()
    if existing_session:
        if existing_session.manifest_sha256 == manifest_hash:
            missing = db.execute(
                select(DbCapture.id).where(
                    DbCapture.session_id == session_id,
                    DbCapture.has_image.is_(True),
                    DbCapture.verified.is_(False),
                )
            ).scalars().all()
            response.status_code = status.HTTP_200_OK
            return SessionStatus(
                session_id=existing_session.id,
                status=existing_session.status,
                missing_images=list(missing),
                received_on=existing_session.received_on,
                completed_on=existing_session.completed_on,
            )
        else:
            raise ConflictImmutableError(f"Session {session_id} already exists with different manifest")

    # 7. Create new session
    new_session = DbSession(
        id=session_id,
        participant_id=canonical_participant.id,
        device_id=device.id,
        card_status=body.card_status,
        card_batch_id=body.card_batch_id,
        capture_date=body.capture_date,
        captured_at=body.captured_at,
        age_hours=body.age_hours,
        gestational_age_weeks=body.gestational_age_weeks,
        flag_jaundice_under_24h=body.flag_jaundice_under_24h,
        flag_danger_signs=body.flag_danger_signs,
        flag_prior_phototherapy=body.flag_prior_phototherapy,
        estimate_mg_dl=body.estimate_mg_dl,
        estimate_low_mg_dl=body.estimate_low_mg_dl,
        estimate_high_mg_dl=body.estimate_high_mg_dl,
        app_version=body.app_version,
        model_version=body.model_version,
        config_version=body.config_version,
        manifest_sha256=manifest_hash,
        status="RECEIVING",
        received_on=today,
    )
    db.add(new_session)
    db.flush()

    missing_uuids = []
    for c in body.captures:
        new_cap = DbCapture(
            id=c.capture_id,
            session_id=session_id,
            attempt_number=c.attempt_number,
            site=c.site,
            capture_metadata=c.capture_metadata.model_dump(),
            quality_status=c.quality_status,
            quality_fail_reasons=c.quality_fail_reasons,
            correction_residual_de=c.correction_residual_de,
            features=c.features,
            has_image=c.has_image,
            sha256=c.image_sha256,
            size_bytes=c.size_bytes,
            verified=False,
        )
        db.add(new_cap)
        if c.has_image:
            missing_uuids.append(c.capture_id)

    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return SessionStatus(
        session_id=session_id,
        status="RECEIVING",
        missing_images=missing_uuids,
        received_on=today,
        completed_on=None,
    )


@router.get(
    "/sessions/{session_id}",
    operation_id="getSessionStatus",
    summary="Session status, including any images still missing.",
    response_model=SessionStatus,
    status_code=status.HTTP_200_OK,
    responses={
        200: {"model": SessionStatus, "description": "Session status."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        404: {"model": Problem, "description": "Session not found."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def get_session_status(
    session_id: UUID = Path(...),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> SessionStatus:
    session_row = db.execute(select(DbSession).where(DbSession.id == session_id)).scalar_one_or_none()
    if not session_row:
        raise NotFoundError(f"Session {session_id} not found")

    missing = db.execute(
        select(DbCapture.id).where(
            DbCapture.session_id == session_id,
            DbCapture.has_image.is_(True),
            DbCapture.verified.is_(False),
        )
    ).scalars().all()

    return SessionStatus(
        session_id=session_row.id,
        status=session_row.status,
        missing_images=list(missing),
        received_on=session_row.received_on,
        completed_on=session_row.completed_on,
    )


@router.put(
    "/sessions/{session_id}/captures/{capture_id}/image",
    operation_id="putCaptureImage",
    summary="Upload one capture image (JPEG) listed in an accepted manifest.",
    response_model=ImageReceipt,
    responses={
        200: {"model": ImageReceipt, "description": "Same image already stored; nothing changed."},
        201: {"model": ImageReceipt, "description": "Image stored and hash verified."},
        400: {"model": Problem, "description": "Malformed request or failed validation."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        404: {"model": Problem, "description": "Session or capture not found."},
        409: {"model": Problem, "description": "Conflicts with existing data."},
        413: {"model": Problem, "description": "Payload exceeds size limit."},
        415: {"model": Problem, "description": "Unsupported media type."},
        422: {"model": Problem, "description": "Well-formed but rejected."},
        426: {"model": Problem, "description": "App version below server minimum."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        507: {"model": Problem, "description": "Insufficient storage."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
async def put_capture_image(
    request: Request,
    response: Response,
    session_id: UUID = Path(...),
    capture_id: UUID = Path(...),
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    content_digest: str = Header(alias="Content-Digest"),
    db: Session = Depends(get_db),
) -> ImageReceipt:
    # Basic Phase A and stub for P17 - Phase A checks were already performed in middleware
    capture_row = db.execute(
        select(DbCapture).where(DbCapture.session_id == session_id, DbCapture.id == capture_id)
    ).scalar_one()

    # Read body bytes and verify hash
    body = await request.body()
    actual_hash = hashlib.sha256(body).hexdigest()
    if actual_hash != capture_row.sha256:
        from app.errors import HashMismatchError
        raise HashMismatchError("Body hash does not match declared digest")

    capture_row.verified = True
    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return ImageReceipt(
        session_status="RECEIVING",
        missing_count=0,
        capture_id=capture_id,
        sha256=capture_row.sha256 or actual_hash,
        size_bytes=len(body),
        verified=True,
    )
