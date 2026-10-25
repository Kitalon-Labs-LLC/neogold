from __future__ import annotations

import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Path, Request, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import (
    ConflictImmutableError,
    ParticipantCodeTakenError,
    ParticipantWithdrawnError,
)
from app.models.api import ParticipantReceipt, ParticipantUpload, Problem
from app.models.db import Device, Participant, ParticipantAlias, Site

router = APIRouter(tags=["Participants"])


@router.put(
    "/participants/{participant_id}",
    operation_id="putParticipant",
    summary="Create a participant (code and consent reference only).",
    response_model=ParticipantReceipt,
    responses={
        200: {
            "model": ParticipantReceipt,
            "description": "Participant already exists with identical values, or was registered as an alias.",
        },
        201: {"model": ParticipantReceipt, "description": "Participant created."},
        400: {"model": Problem, "description": "Malformed request or failed validation."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        409: {"model": Problem, "description": "Conflicts with existing data."},
        426: {"model": Problem, "description": "App version below server minimum."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def put_participant(
    request: Request,
    response: Response,
    body: ParticipantUpload,
    participant_id: UUID = Path(...),
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> ParticipantReceipt:
    device_id: UUID = request.scope["state"]["device_id"]
    device = db.execute(select(Device).where(Device.id == device_id)).scalar_one()
    site = db.execute(select(Site).where(Site.id == device.site_id)).scalar_one()

    today = datetime.datetime.now(datetime.UTC).date()

    # Case A: participant_id exists as a Participant
    existing_p = db.execute(select(Participant).where(Participant.id == participant_id)).scalar_one_or_none()
    if existing_p:
        if existing_p.withdrawn_on is not None:
            raise ParticipantWithdrawnError("Participant has withdrawn consent")
        if (
            existing_p.site_id == site.id
            and existing_p.participant_code == body.participant_code
            and existing_p.consent_form_id == body.consent_form_id
            and existing_p.consent_date == body.consent_date
        ):
            response.status_code = status.HTTP_200_OK
            return ParticipantReceipt(
                participant_id=participant_id,
                canonical_participant_id=participant_id,
                site_code=site.site_code,
                created_on=existing_p.created_on,
            )
        else:
            raise ConflictImmutableError(f"Participant {participant_id} already exists with different values")

    # Case B: participant_id exists as an alias
    existing_alias = db.execute(
        select(ParticipantAlias).where(ParticipantAlias.alias_id == participant_id)
    ).scalar_one_or_none()
    if existing_alias:
        canonical = db.execute(
            select(Participant).where(Participant.id == existing_alias.participant_id)
        ).scalar_one()
        if canonical.withdrawn_on is not None:
            raise ParticipantWithdrawnError("Participant has withdrawn consent")
        if (
            canonical.site_id == site.id
            and canonical.participant_code == body.participant_code
            and canonical.consent_form_id == body.consent_form_id
        ):
            response.status_code = status.HTTP_200_OK
            return ParticipantReceipt(
                participant_id=participant_id,
                canonical_participant_id=canonical.id,
                site_code=site.site_code,
                created_on=canonical.created_on,
            )
        else:
            raise ConflictImmutableError(f"Participant alias {participant_id} exists with different values")

    # Case C: Same participant_code already registered at this site
    same_code_p = db.execute(
        select(Participant).where(
            Participant.site_id == site.id,
            Participant.participant_code == body.participant_code,
        )
    ).scalar_one_or_none()
    if same_code_p:
        if same_code_p.withdrawn_on is not None:
            raise ParticipantWithdrawnError("Participant has withdrawn consent")
        if same_code_p.consent_form_id == body.consent_form_id:
            # Register as alias!
            alias = ParticipantAlias(
                alias_id=participant_id,
                participant_id=same_code_p.id,
                device_id=device.id,
                created_on=today,
            )
            db.add(alias)
            db.commit()
            response.status_code = status.HTTP_200_OK
            return ParticipantReceipt(
                participant_id=participant_id,
                canonical_participant_id=same_code_p.id,
                site_code=site.site_code,
                created_on=same_code_p.created_on,
            )
        else:
            raise ParticipantCodeTakenError(
                f"Participant code {body.participant_code} is already in use with a different consent form ID"
            )

    # Case D: New participant
    new_p = Participant(
        id=participant_id,
        site_id=site.id,
        participant_code=body.participant_code,
        consent_form_id=body.consent_form_id,
        consent_date=body.consent_date,
        created_on=today,
    )
    db.add(new_p)
    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return ParticipantReceipt(
        participant_id=participant_id,
        canonical_participant_id=participant_id,
        site_code=site.site_code,
        created_on=today,
    )
