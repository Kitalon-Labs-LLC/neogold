from __future__ import annotations

import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class BaseApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Health(BaseApiModel):
    status: Literal["ok", "degraded"]
    api_version: str
    server_time: datetime.datetime


class Jwk(BaseApiModel):
    kty: Literal["EC"]
    crv: Literal["P-256"]
    x: Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{43}$")]
    y: Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{43}$")]


class DeviceInfo(BaseApiModel):
    manufacturer: Annotated[str, Field(max_length=64)]
    model: Annotated[str, Field(max_length=64)]
    android_api: Annotated[int, Field(ge=26, le=60)]
    app_version: Annotated[str, Field(pattern=r"^\d+\.\d+\.\d+$")]
    camera_hardware_level: Literal["LEGACY", "LIMITED", "FULL", "LEVEL_3", "EXTERNAL"]
    qualification: Literal["QUALIFIED", "RESEARCH_ONLY"]


class EnrollmentRequest(BaseApiModel):
    enrollment_code: Annotated[
        str,
        Field(pattern=r"^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$"),
    ]
    device_public_key: Jwk
    key_attestation_chain: Annotated[list[str], Field(max_length=6)] | None = None
    device: DeviceInfo


class UploadPolicy(BaseApiModel):
    wifi_only: bool
    max_image_bytes: Annotated[int, Field(le=20000000)]
    max_requests_per_minute: int
    timestamp_precision: Literal["DAY_PLUS_OFFSETS", "FULL"]
    time_zone: str


class EnrollmentResponse(BaseApiModel):
    data_generation: Annotated[int, Field(ge=1)]
    device_id: UUID
    study_code: str
    study_name: str
    site_code: str
    server_time: datetime.datetime
    upload_policy: UploadPolicy


class DeviceStatus(BaseApiModel):
    data_generation: Annotated[int, Field(ge=1)]
    device_id: UUID
    site_code: str
    study_status: Literal["ACTIVE", "PAUSED", "CLOSED"]
    revoked: bool
    server_time: datetime.datetime
    min_app_version: str
    upload_policy: UploadPolicy


class CardBatchUpload(BaseApiModel):
    qr_payload: Annotated[str, Field(max_length=4096)]


class CardBatchReceipt(BaseApiModel):
    batch_id: Annotated[str, Field(pattern=r"^[A-Z0-9][A-Z0-9-]{2,31}$")]
    issuer_id: str
    signature_valid: bool
    expires_at: datetime.date


class ParticipantUpload(BaseApiModel):
    participant_code: Annotated[str, Field(pattern=r"^[A-Z]{2,5}-[0-9]{3,6}$")]
    consent_form_id: Annotated[str, Field(pattern=r"^[A-Z]{2,5}-[A-Z0-9-]{2,26}$")]
    consent_date: datetime.date


class ParticipantReceipt(BaseApiModel):
    participant_id: UUID
    canonical_participant_id: UUID
    site_code: str
    created_on: datetime.date


class ImageReceipt(BaseApiModel):
    session_status: Literal["RECEIVING", "COMPLETE", "REJECTED"]
    missing_count: Annotated[int, Field(ge=0)]
    capture_id: UUID
    sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    size_bytes: Annotated[int, Field(ge=1, le=20000000)]
    verified: bool


class CaptureMetadata(BaseApiModel):
    iso: int | None = None
    exposure_ns: int | None = None
    ae_locked: bool
    awb_locked: bool
    camera_hardware_level: Literal["LEGACY", "LIMITED", "FULL", "LEVEL_3", "EXTERNAL"]
    color_gains: Annotated[list[float], Field(max_length=4)] | None = None


QualityFailReason = Literal[
    "NO_CARD",
    "CARD_ANGLE",
    "NONUNIFORM_PATCHES",
    "BLUR",
    "GLARE",
    "UNDEREXPOSED",
    "OVEREXPOSED",
    "UNEVEN_LIGHT",
    "HIGH_RESIDUAL",
    "WINDOW_NOT_SKIN",
]


class CaptureManifest(BaseApiModel):
    capture_id: UUID
    attempt_number: Annotated[int, Field(ge=1, le=3)]
    site: Literal["STERNUM", "FOREHEAD", "SCLERA_FLASH", "SCLERA_NOFLASH", "CALIBRATION"]
    quality_status: Literal["PASSED", "FAILED"]
    quality_fail_reasons: list[QualityFailReason] | None = None
    correction_residual_de: Annotated[float, Field(ge=0)] | None = None
    features: dict[str, float] | None = None
    capture_metadata: CaptureMetadata
    has_image: bool
    image_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")] | None = Field(...)
    size_bytes: Annotated[int, Field(ge=1, le=20000000)] | None = Field(...)


class SessionManifest(BaseApiModel):
    participant_id: UUID
    card_status: Literal["VERIFIED", "EXPIRED", "UNVERIFIED"]
    card_batch_id: Annotated[str, Field(pattern=r"^[A-Z0-9][A-Z0-9-]{2,31}$")] | None = Field(...)
    capture_date: datetime.date
    captured_at: datetime.datetime | None = None
    age_hours: Annotated[float, Field(ge=0, le=720, multiple_of=0.1)]
    gestational_age_weeks: Annotated[int, Field(ge=22, le=44)]
    flag_jaundice_under_24h: bool
    flag_danger_signs: bool
    flag_prior_phototherapy: bool
    estimate_mg_dl: Annotated[float, Field(ge=0, le=40, multiple_of=0.1)] | None = None
    estimate_low_mg_dl: Annotated[float, Field(ge=0, le=40, multiple_of=0.1)] | None = None
    estimate_high_mg_dl: Annotated[float, Field(ge=0, le=40, multiple_of=0.1)] | None = None
    app_version: Annotated[str, Field(pattern=r"^\d+\.\d+\.\d+$")]
    model_version: str | None = None
    config_version: str
    captures: Annotated[list[CaptureManifest], Field(min_length=1, max_length=15)]


class SessionStatus(BaseApiModel):
    session_id: UUID
    status: Literal["RECEIVING", "COMPLETE", "REJECTED"]
    missing_images: list[UUID] | None = None
    received_on: datetime.date
    completed_on: datetime.date | None = None


class TsbUpload(BaseApiModel):
    participant_id: UUID
    session_id: UUID | None = None
    tsb_mg_dl: Annotated[float, Field(gt=0, le=40, multiple_of=0.1)]
    minutes_from_capture: Annotated[int, Field(ge=-10080, le=10080)] | None = None
    draw_time: datetime.datetime | None = None
    method: Literal["LAB", "POINT_OF_CARE", "OTHER"]


class TsbReceipt(BaseApiModel):
    tsb_id: UUID
    participant_id: UUID
    linked: bool
    received_on: datetime.date


class FieldError(BaseApiModel):
    field: str
    message: str


ErrorCode = Literal[
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
]


class Problem(BaseApiModel):
    type: str
    title: str
    status: int
    code: ErrorCode
    retryable: bool
    request_id: str
    detail: str | None = None
    instance: str | None = None
    errors: list[FieldError] | None = None
    server_time: datetime.datetime | None = None
    min_app_version: str | None = None
