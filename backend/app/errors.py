from __future__ import annotations

import datetime
from typing import Any

from fastapi.responses import JSONResponse

from app.models.api import Problem  # noqa: F401


class NeoGoldError(Exception):
    def __init__(
        self,
        status: int,
        code: str,
        title: str,
        retryable: bool,
        detail: str | None = None,
        errors: list[dict[str, str]] | None = None,
        server_time: datetime.datetime | None = None,
        min_app_version: str | None = None,
        headers: dict[str, str] | None = None,
    ):
        super().__init__(title)
        self.status = status
        self.code = code
        self.title = title
        self.retryable = retryable
        self.detail = detail
        self.errors = errors
        self.server_time = server_time
        self.min_app_version = min_app_version
        self.headers = headers or {}


class ValidationFailedError(NeoGoldError):
    def __init__(self, detail: str = "Request validation failed", errors: list[dict[str, str]] | None = None):
        super().__init__(
            status=400,
            code="VALIDATION_FAILED",
            title="Validation failed",
            retryable=False,
            detail=detail,
            errors=errors,
        )


class AuthMissingError(NeoGoldError):
    def __init__(self, detail: str = "Missing Authorization header"):
        super().__init__(
            status=401,
            code="AUTH_MISSING",
            title="Authentication missing",
            retryable=False,
            detail=detail,
        )


class AuthInvalidSignatureError(NeoGoldError):
    def __init__(self, detail: str = "Invalid signature or JOSE header"):
        super().__init__(
            status=401,
            code="AUTH_INVALID_SIGNATURE",
            title="Invalid signature",
            retryable=False,
            detail=detail,
        )


class AuthClaimMismatchError(NeoGoldError):
    def __init__(self, detail: str = "Token claims mismatch or missing"):
        super().__init__(
            status=401,
            code="AUTH_CLAIM_MISMATCH",
            title="Token claim mismatch",
            retryable=False,
            detail=detail,
        )


class AuthExpiredError(NeoGoldError):
    def __init__(self, server_time: datetime.datetime, detail: str = "Device assertion token has expired"):
        super().__init__(
            status=401,
            code="AUTH_EXPIRED",
            title="Token expired",
            retryable=True,
            detail=detail,
            server_time=server_time,
        )


class ClockSkewError(NeoGoldError):
    def __init__(self, server_time: datetime.datetime, detail: str = "Device clock is out of range"):
        super().__init__(
            status=401,
            code="CLOCK_SKEW",
            title="Device clock is out of range",
            retryable=True,
            detail=detail,
            server_time=server_time,
        )


class AuthReplayError(NeoGoldError):
    def __init__(self, detail: str = "Token replay detected"):
        super().__init__(
            status=401,
            code="AUTH_REPLAY",
            title="Authentication replay",
            retryable=True,
            detail=detail,
        )


class DeviceUnknownError(NeoGoldError):
    def __init__(self, detail: str = "Device not registered"):
        super().__init__(
            status=401,
            code="DEVICE_UNKNOWN",
            title="Device unknown",
            retryable=False,
            detail=detail,
        )


class DeviceRevokedError(NeoGoldError):
    def __init__(self, detail: str = "This device has been revoked"):
        super().__init__(
            status=403,
            code="DEVICE_REVOKED",
            title="Device revoked",
            retryable=False,
            detail=detail,
        )


class StudyPausedError(NeoGoldError):
    def __init__(self, detail: str = "Study intake is paused"):
        super().__init__(
            status=403,
            code="STUDY_PAUSED",
            title="Study paused",
            retryable=True,
            detail=detail,
        )


class StudyClosedError(NeoGoldError):
    def __init__(self, detail: str = "Study intake is closed"):
        super().__init__(
            status=403,
            code="STUDY_CLOSED",
            title="Study closed",
            retryable=False,
            detail=detail,
        )


class EnrollmentCodeInvalidError(NeoGoldError):
    def __init__(self, detail: str = "Enrollment code not recognised"):
        super().__init__(
            status=403,
            code="ENROLLMENT_CODE_INVALID",
            title="Invalid enrollment code",
            retryable=False,
            detail=detail,
        )


class EnrollmentCodeExpiredError(NeoGoldError):
    def __init__(self, detail: str = "Enrollment code has expired"):
        super().__init__(
            status=403,
            code="ENROLLMENT_CODE_EXPIRED",
            title="Enrollment code expired",
            retryable=False,
            detail=detail,
        )


class EnrollmentCodeUsedError(NeoGoldError):
    def __init__(self, detail: str = "Enrollment code already used"):
        super().__init__(
            status=403,
            code="ENROLLMENT_CODE_USED",
            title="Enrollment code used",
            retryable=False,
            detail=detail,
        )


class DeviceKeyAlreadyEnrolledError(NeoGoldError):
    def __init__(self, detail: str = "This device public key is already registered"):
        super().__init__(
            status=409,
            code="DEVICE_KEY_ALREADY_ENROLLED",
            title="Device key already enrolled",
            retryable=False,
            detail=detail,
        )


class IdempotencyKeyReusedError(NeoGoldError):
    def __init__(self, detail: str = "Idempotency key reused with different request payload"):
        super().__init__(
            status=409,
            code="IDEMPOTENCY_KEY_REUSED",
            title="Idempotency key reused",
            retryable=False,
            detail=detail,
        )


class RequestInProgressError(NeoGoldError):
    def __init__(self, detail: str = "The same key is currently being processed"):
        super().__init__(
            status=409,
            code="REQUEST_IN_PROGRESS",
            title="Request in progress",
            retryable=True,
            detail=detail,
        )


class ConflictImmutableError(NeoGoldError):
    def __init__(self, detail: str = "Resource already exists with different content"):
        super().__init__(
            status=409,
            code="CONFLICT_IMMUTABLE",
            title="Resource immutable conflict",
            retryable=False,
            detail=detail,
        )


class ParticipantCodeTakenError(NeoGoldError):
    def __init__(self, detail: str = "Participant code is already in use with a different consent form ID"):
        super().__init__(
            status=409,
            code="PARTICIPANT_CODE_TAKEN",
            title="Participant code taken",
            retryable=False,
            detail=detail,
        )


class ParticipantUnknownError(NeoGoldError):
    def __init__(self, detail: str = "Referenced participant not found"):
        super().__init__(
            status=422,
            code="PARTICIPANT_UNKNOWN",
            title="Participant unknown",
            retryable=True,
            detail=detail,
        )


class ParticipantWithdrawnError(NeoGoldError):
    def __init__(self, detail: str = "Participant consent was withdrawn"):
        super().__init__(
            status=422,
            code="PARTICIPANT_WITHDRAWN",
            title="Participant withdrawn",
            retryable=False,
            detail=detail,
        )


class SessionUnknownError(NeoGoldError):
    def __init__(self, detail: str = "Session manifest not uploaded", status: int = 404):
        super().__init__(
            status=status,
            code="SESSION_UNKNOWN",
            title="Session unknown",
            retryable=True,
            detail=detail,
        )


class CaptureNotInManifestError(NeoGoldError):
    def __init__(self, detail: str = "Capture not found in manifest or has no image"):
        super().__init__(
            status=404,
            code="CAPTURE_NOT_IN_MANIFEST",
            title="Capture not in manifest",
            retryable=False,
            detail=detail,
        )


class CardBatchUnknownError(NeoGoldError):
    def __init__(self, detail: str = "Card batch not registered"):
        super().__init__(
            status=422,
            code="CARD_BATCH_UNKNOWN",
            title="Card batch unknown",
            retryable=True,
            detail=detail,
        )


class CardBatchInvalidError(NeoGoldError):
    def __init__(self, detail: str = "Card batch signature invalid or issuer untrusted"):
        super().__init__(
            status=422,
            code="CARD_BATCH_INVALID",
            title="Card batch invalid",
            retryable=False,
            detail=detail,
        )


class HashMismatchError(NeoGoldError):
    def __init__(self, detail: str = "Body hash does not match declared digest or manifest"):
        super().__init__(
            status=422,
            code="HASH_MISMATCH",
            title="Hash mismatch",
            retryable=True,
            detail=detail,
        )


class ImageInvalidError(NeoGoldError):
    def __init__(self, detail: str = "Image is not a valid baseline JPEG or dimensions exceed 8192px"):
        super().__init__(
            status=422,
            code="IMAGE_INVALID",
            title="Image invalid",
            retryable=False,
            detail=detail,
        )


class ImageMetadataNotAllowedError(NeoGoldError):
    def __init__(self, detail: str = "JPEG contains prohibited metadata segments"):
        super().__init__(
            status=422,
            code="IMAGE_METADATA_NOT_ALLOWED",
            title="Image metadata not allowed",
            retryable=False,
            detail=detail,
        )


class PayloadTooLargeError(NeoGoldError):
    def __init__(self, detail: str = "Payload exceeds size limit"):
        super().__init__(
            status=413,
            code="PAYLOAD_TOO_LARGE",
            title="Payload too large",
            retryable=False,
            detail=detail,
        )


class UnsupportedMediaTypeError(NeoGoldError):
    def __init__(self, detail: str = "Unsupported media type"):
        super().__init__(
            status=415,
            code="UNSUPPORTED_MEDIA_TYPE",
            title="Unsupported media type",
            retryable=False,
            detail=detail,
        )


class NotFoundError(NeoGoldError):
    def __init__(self, detail: str = "Resource not found"):
        super().__init__(
            status=404,
            code="NOT_FOUND",
            title="Not found",
            retryable=False,
            detail=detail,
        )


class AppVersionUnsupportedError(NeoGoldError):
    def __init__(self, min_app_version: str, detail: str = "App version below study minimum"):
        super().__init__(
            status=426,
            code="APP_VERSION_UNSUPPORTED",
            title="App version unsupported",
            retryable=False,
            detail=detail,
            min_app_version=min_app_version,
        )


class RateLimitedError(NeoGoldError):
    def __init__(self, retry_after: int = 1, detail: str = "Rate limit exceeded"):
        super().__init__(
            status=429,
            code="RATE_LIMITED",
            title="Rate limited",
            retryable=True,
            detail=detail,
            headers={"Retry-After": str(retry_after)},
        )


class StorageFullError(NeoGoldError):
    def __init__(self, detail: str = "Server storage is full"):
        super().__init__(
            status=507,
            code="STORAGE_FULL",
            title="Insufficient storage",
            retryable=True,
            detail=detail,
        )


class MaintenanceError(NeoGoldError):
    def __init__(self, retry_after: int = 60, detail: str = "Server undergoing maintenance"):
        super().__init__(
            status=503,
            code="MAINTENANCE",
            title="Service unavailable",
            retryable=True,
            detail=detail,
            headers={"Retry-After": str(retry_after)},
        )


class ServerError(NeoGoldError):
    def __init__(self, detail: str = "Unexpected server error"):
        super().__init__(
            status=500,
            code="SERVER_ERROR",
            title="Internal server error",
            retryable=True,
            detail=detail,
        )


def build_problem_payload(
    error: NeoGoldError,
    request_id: str,
    instance: str | None = None,
) -> dict[str, Any]:
    kebab_code = error.code.lower().replace("_", "-")
    payload: dict[str, Any] = {
        "type": f"https://neogold.example/problems/{kebab_code}",
        "title": error.title,
        "status": error.status,
        "code": error.code,
        "retryable": error.retryable,
        "request_id": request_id,
    }
    if error.detail is not None:
        payload["detail"] = error.detail
    if instance is not None:
        payload["instance"] = instance
    if error.errors is not None:
        payload["errors"] = error.errors
    if error.server_time is not None:
        payload["server_time"] = error.server_time.strftime("%Y-%m-%dT%H:%M:%SZ")
    if error.min_app_version is not None:
        payload["min_app_version"] = error.min_app_version
    return payload


def problem_response(
    error: NeoGoldError,
    request_id: str,
    instance: str | None = None,
) -> JSONResponse:
    payload = build_problem_payload(error, request_id, instance)
    headers = {"Content-Type": "application/problem+json"}
    if error.headers:
        headers.update(error.headers)
    return JSONResponse(status_code=error.status, content=payload, headers=headers)
