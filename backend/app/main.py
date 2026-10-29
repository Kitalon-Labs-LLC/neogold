from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.utils import get_openapi
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.config import settings
from app.errors import (
    NeoGoldError,
    NotFoundError,
    ServerError,
    ValidationFailedError,
    problem_response,
)
from app.middleware.auth import DeviceAuthMiddleware
from app.middleware.timing import TimingMiddleware
from app.routers import (
    card_batches,
    device,
    enrollments,
    health,
    participants,
    sessions,
    tsb,
)

logger = logging.getLogger("neogold")


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """K12 (doc 8 Section 4) must be set and strong before the server takes a request: stop at start, not on the first
    enrollment. This runs at server start and not at import, so tools that only read the API description (diff_spec,
    the drift inventory) can import the app without the secret."""
    settings.enrollment_code_key()
    yield


app = FastAPI(
    lifespan=lifespan,
    title="NeoGold Study Intake API",
    version=settings.API_VERSION,
    summary="Receives consented research uploads from enrolled NeoGold phones.",
    description="""One Study Intake Service runs per study, hosted under the partner's data governance.
Screening mode never calls this API. Only research mode does, and only on Wi-Fi.

Authentication: every call except /health and /enrollments carries a short-lived JWT
signed by the phone's hardware-backed key (see the deviceAssertion security scheme).

Errors use RFC 9457 Problem Details (`application/problem+json`) with a stable `code`.
All request bodies reject unknown fields, so personal data cannot slip in by accident.
""",
    license_info={
        "name": "MIT",
        "identifier": "MIT",
    },
    servers=[
        {
            "url": "https://{host}/v1",
            "variables": {
                "host": {
                    "default": "staging.neogold.example",
                    "description": "Study server host name, issued by the study coordinator.",
                }
            },
        }
    ],
)

# Include routers without prefix for primary OpenAPI paths
app.include_router(health.router)
app.include_router(enrollments.router)
app.include_router(device.router)
app.include_router(card_batches.router)
app.include_router(participants.router)
app.include_router(sessions.router)
app.include_router(tsb.router)

# Also mount under /v1 for requests arriving with /v1 prefix, without duplicating in schema
app.include_router(health.router, prefix="/v1", include_in_schema=False)
app.include_router(enrollments.router, prefix="/v1", include_in_schema=False)
app.include_router(device.router, prefix="/v1", include_in_schema=False)
app.include_router(card_batches.router, prefix="/v1", include_in_schema=False)
app.include_router(participants.router, prefix="/v1", include_in_schema=False)
app.include_router(sessions.router, prefix="/v1", include_in_schema=False)
app.include_router(tsb.router, prefix="/v1", include_in_schema=False)

# Add middlewares in outer-to-inner order:
# Timing first (outermost) so t0 is recorded at header arrival
app.add_middleware(DeviceAuthMiddleware)
app.add_middleware(TimingMiddleware)


@app.exception_handler(NeoGoldError)
async def neogold_error_handler(request: Request, exc: NeoGoldError):
    request_id = request.scope.get("state", {}).get("request_id", "00000000000000000000000000")
    return problem_response(exc, request_id, request.url.path)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    request_id = request.scope.get("state", {}).get("request_id", "00000000000000000000000000")
    errs = []
    for err in exc.errors():
        field_parts = [str(x) for x in err.get("loc", []) if x not in ("body", "header", "path", "query")]
        field_name = ".".join(field_parts) or "body"
        errs.append({"field": field_name, "message": err.get("msg", "Invalid value")})
    ne_err = ValidationFailedError(detail="Request validation failed", errors=errs)
    return problem_response(ne_err, request_id, request.url.path)


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    request_id = request.scope.get("state", {}).get("request_id", "00000000000000000000000000")
    if exc.status_code == 404:
        err = NotFoundError("Resource not found")
        return problem_response(err, request_id, request.url.path)
    elif exc.status_code == 405:
        err = ValidationFailedError("Method not allowed")
        return problem_response(err, request_id, request.url.path)
    else:
        err = ServerError(str(exc.detail))
        return problem_response(err, request_id, request.url.path)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    request_id = request.scope.get("state", {}).get("request_id", "00000000000000000000000000")
    # Only the type is logged: the message and the traceback of a database error carry the statement's parameters, which
    # include participant codes (doc 8 Section 6.6). The request_id leads to the rest.
    logger.error("Unhandled server error [request_id=%s]: %s", request_id, type(exc).__name__)
    err = ServerError("Unexpected server failure")
    return problem_response(err, request_id, request.url.path)


def custom_openapi():
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(
        title=app.title,
        version=app.version,
        openapi_version="3.1.0",
        summary=app.summary,
        description=app.description,
        routes=app.routes,
        servers=app.servers,
        license_info=app.license_info,
    )
    # Ensure security scheme
    schema["security"] = [{"deviceAssertion": []}]
    if "components" not in schema:
        schema["components"] = {}
    if "securitySchemes" not in schema["components"]:
        schema["components"]["securitySchemes"] = {}
    schema["components"]["securitySchemes"]["deviceAssertion"] = {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "Device assertion signed with ES256 hardware-backed key.",
    }
    if "schemas" not in schema["components"]:
        schema["components"]["schemas"] = {}

    schema["components"]["schemas"]["Uuid"] = {
        "type": "string",
        "format": "uuid",
    }
    schema["components"]["schemas"]["BatchId"] = {
        "type": "string",
        "pattern": "^[A-Z0-9][A-Z0-9-]{2,31}$",
        "examples": ["NG-2026-001"],
    }
    schema["components"]["schemas"]["MgDl"] = {
        "type": "number",
        "minimum": 0,
        "maximum": 40,
        "multipleOf": 0.1,
    }
    schema["components"]["schemas"]["ErrorCode"] = {
        "type": "string",
        "enum": [
            "VALIDATION_FAILED",
            "AUTH_MISSING",
            "AUTH_INVALID_SIGNATURE",
            "AUTH_EXPIRED",
            "AUTH_REPLAY",
            "AUTH_CLAIM_MISMATCH",
            "CLOCK_SKEW",
            "DEVICE_UNKNOWN",
            "DEVICE_REVOKED",
            "STUDY_PAUSED",
            "STUDY_CLOSED",
            "ENROLLMENT_CODE_INVALID",
            "ENROLLMENT_CODE_EXPIRED",
            "ENROLLMENT_CODE_USED",
            "DEVICE_KEY_ALREADY_ENROLLED",
            "IDEMPOTENCY_KEY_REUSED",
            "REQUEST_IN_PROGRESS",
            "CONFLICT_IMMUTABLE",
            "PARTICIPANT_CODE_TAKEN",
            "PARTICIPANT_UNKNOWN",
            "PARTICIPANT_WITHDRAWN",
            "SESSION_UNKNOWN",
            "CAPTURE_NOT_IN_MANIFEST",
            "CARD_BATCH_INVALID",
            "CARD_BATCH_UNKNOWN",
            "HASH_MISMATCH",
            "IMAGE_INVALID",
            "IMAGE_METADATA_NOT_ALLOWED",
            "PAYLOAD_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE",
            "NOT_FOUND",
            "APP_VERSION_UNSUPPORTED",
            "RATE_LIMITED",
            "STORAGE_FULL",
            "MAINTENANCE",
            "SERVER_ERROR",
        ],
    }

    app.openapi_schema = schema
    return app.openapi_schema


app.openapi = custom_openapi
