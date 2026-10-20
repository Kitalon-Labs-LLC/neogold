from __future__ import annotations

import datetime

from fastapi import APIRouter

from app.config import settings
from app.models.api import Health, Problem

router = APIRouter(tags=["System"])


@router.get(
    "/health",
    operation_id="getHealth",
    summary="Liveness check and server time (no authentication).",
    response_model=Health,
    status_code=200,
    responses={
        200: {"model": Health, "description": "Service is up."},
        429: {"model": Problem, "description": "Rate limit exceeded."},
        503: {"model": Problem, "description": "Maintenance or temporary failure."},
        "5XX": {"model": Problem, "description": "Unexpected server failure."},
    },
)
def get_health() -> Health:
    now = datetime.datetime.now(datetime.UTC)
    return Health(
        status="ok",
        api_version=settings.API_VERSION,
        server_time=now,
    )
