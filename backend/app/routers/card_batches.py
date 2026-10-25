from __future__ import annotations

import base64
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Path, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.errors import (
    ConflictImmutableError,
    ValidationFailedError,
)
from app.models.api import CardBatchReceipt, CardBatchUpload, Problem
from app.models.db import CardBatch
from app.security.card_batch import verify_card_batch_payload

router = APIRouter(tags=["Card batches"])


@router.put(
    "/card-batches/{batch_id}",
    operation_id="putCardBatch",
    summary="Register a card batch seen by this phone (sent once per batch).",
    response_model=CardBatchReceipt,
    responses={
        200: {"model": CardBatchReceipt, "description": "Batch already known; nothing changed."},
        201: {"model": CardBatchReceipt, "description": "Batch registered."},
        400: {"model": Problem, "description": "Malformed request or failed validation."},
        401: {"model": Problem, "description": "Missing, invalid, expired, or replayed device assertion."},
        403: {"model": Problem, "description": "Device revoked or study closed."},
        422: {"model": Problem, "description": "Well-formed but rejected."},
        426: {"model": Problem, "description": "App version below server minimum."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Service unavailable."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def put_card_batch(
    response: Response,
    body: CardBatchUpload,
    batch_id: str = Path(..., pattern=r"^[A-Z0-9][A-Z0-9-]{2,31}$"),
    idempotency_key: UUID = Header(alias="Idempotency-Key"),
    x_neogold_app_version: str = Header(alias="X-NeoGold-App-Version"),
    db: Session = Depends(get_db),
) -> CardBatchReceipt:
    try:
        raw_payload = base64.b64decode(body.qr_payload)
    except Exception as e:
        raise ValidationFailedError("Invalid base64 encoding in qr_payload") from e

    parsed = verify_card_batch_payload(raw_payload, settings.TRUST_LIST_PATH)

    if parsed["batch_id"] != batch_id:
        raise ValidationFailedError(f"Payload batch_id {parsed['batch_id']} does not match path {batch_id}")

    existing = db.execute(select(CardBatch).where(CardBatch.batch_id == batch_id)).scalar_one_or_none()
    if existing:
        if bytes(existing.qr_payload) == raw_payload:
            response.status_code = status.HTTP_200_OK
            return CardBatchReceipt(
                batch_id=existing.batch_id,
                issuer_id=existing.issuer_id,
                signature_valid=True,
                expires_at=existing.expires_at,
            )
        else:
            raise ConflictImmutableError(f"Card batch {batch_id} already exists with different payload")

    new_batch = CardBatch(
        batch_id=batch_id,
        issuer_id=parsed["issuer_id"],
        qr_payload=raw_payload,
        issuer_key_id=parsed["issuer_id"],
        reference_values=parsed["reference_values"],
        issued_at=parsed["issued_at"],
        expires_at=parsed["expires_at"],
    )
    db.add(new_batch)
    db.commit()

    response.status_code = status.HTTP_201_CREATED
    return CardBatchReceipt(
        batch_id=new_batch.batch_id,
        issuer_id=new_batch.issuer_id,
        signature_valid=True,
        expires_at=new_batch.expires_at,
    )
