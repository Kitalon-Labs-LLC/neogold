from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    Numeric,
    SmallInteger,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID

from app.database import Base


class Study(Base):
    __tablename__ = "study"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    study_code = Column(String, nullable=False, unique=True)
    name = Column(String, nullable=False)
    partner_institution = Column(String, nullable=False)
    ethics_reference = Column(String, nullable=False)
    timestamp_precision = Column(
        String,
        nullable=False,
        server_default=text("'DAY_PLUS_OFFSETS'"),
    )
    retention_until = Column(Date, nullable=False)
    time_zone = Column(String, nullable=False, server_default=text("'Asia/Dhaka'"))
    min_app_version = Column(String, nullable=False, server_default=text("'1.0.0'"))
    wifi_only = Column(Boolean, nullable=False, server_default=text("true"))
    max_image_bytes = Column(Integer, nullable=False, server_default=text("20000000"))
    max_requests_per_minute = Column(Integer, nullable=False, server_default=text("60"))
    data_generation = Column(BigInteger, nullable=False, server_default=text("1"))
    status = Column(String, nullable=False, server_default=text("'ACTIVE'"))
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))

    __table_args__ = (
        CheckConstraint(
            "timestamp_precision IN ('DAY_PLUS_OFFSETS','FULL')",
            name="study_timestamp_precision_check",
        ),
        CheckConstraint(
            "max_image_bytes BETWEEN 1 AND 20000000",
            name="study_max_image_bytes_check",
        ),
        CheckConstraint(
            "status IN ('ACTIVE','PAUSED','CLOSED')",
            name="study_status_check",
        ),
    )


class Site(Base):
    __tablename__ = "site"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    study_id = Column(UUID(as_uuid=True), ForeignKey("study.id"), nullable=False)
    site_code = Column(String, nullable=False)
    name = Column(String, nullable=False)
    is_test = Column(Boolean, nullable=False, server_default=text("false"))
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))

    __table_args__ = (
        UniqueConstraint("study_id", "site_code", name="site_study_id_site_code_key"),
    )


class Device(Base):
    __tablename__ = "device"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    site_id = Column(UUID(as_uuid=True), ForeignKey("site.id"), nullable=False)
    public_key = Column(JSONB, nullable=False)
    key_thumbprint = Column(String, nullable=False, unique=True)
    key_algorithm = Column(String, nullable=False)
    attestation = Column(String, nullable=False, server_default=text("'NONE'"))
    manufacturer = Column(String, nullable=False)
    device_model = Column(String, nullable=False)
    android_api = Column(SmallInteger, nullable=False)
    camera_hardware_level = Column(String, nullable=False)
    qualification = Column(String, nullable=False)
    app_version = Column(String, nullable=False)
    enrolled_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))
    last_seen_on = Column(Date, nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    revoked_reason = Column(String, nullable=True)

    __table_args__ = (
        CheckConstraint("key_algorithm IN ('ES256')", name="device_key_algorithm_check"),
        CheckConstraint("attestation IN ('NONE','VERIFIED','FAILED')", name="device_attestation_check"),
        CheckConstraint("android_api >= 26", name="device_android_api_check"),
        CheckConstraint("qualification IN ('QUALIFIED','RESEARCH_ONLY')", name="device_qualification_check"),
        CheckConstraint(
            "revoked_reason IN ('LOST','STOLEN','REASSIGNED','SELF_UNENROLLED','COMPROMISED','STUDY_ENDED')",
            name="device_revoked_reason_check",
        ),
        Index("idx_device_site", "site_id", postgresql_where=text("revoked_at IS NULL")),
    )


class EnrollmentCode(Base):
    __tablename__ = "enrollment_code"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    study_id = Column(UUID(as_uuid=True), ForeignKey("study.id"), nullable=False)
    site_id = Column(UUID(as_uuid=True), ForeignKey("site.id"), nullable=False)
    code_hash = Column(String, nullable=False, unique=True)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    used_at = Column(DateTime(timezone=True), nullable=True)
    device_id = Column(UUID(as_uuid=True), ForeignKey("device.id", name="enrollment_code_device_fk"), nullable=True)
    enroll_idempotency_key = Column(UUID(as_uuid=True), nullable=True)
    enroll_key_thumbprint = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))


class AuthNonce(Base):
    __tablename__ = "auth_nonce"

    jti = Column(UUID(as_uuid=True), primary_key=True)
    device_id = Column(UUID(as_uuid=True), ForeignKey("device.id"), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        Index("idx_auth_nonce_expiry", "expires_at"),
    )


class CardBatch(Base):
    __tablename__ = "card_batch"

    batch_id = Column(String, primary_key=True)
    issuer_id = Column(String, nullable=False)
    qr_payload = Column(LargeBinary, nullable=False)
    issuer_key_id = Column(String, nullable=False)
    reference_values = Column(JSONB, nullable=False)
    issued_at = Column(Date, nullable=False)
    expires_at = Column(Date, nullable=False)
    first_seen_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))


class Participant(Base):
    __tablename__ = "participant"

    id = Column(UUID(as_uuid=True), primary_key=True)
    site_id = Column(UUID(as_uuid=True), ForeignKey("site.id"), nullable=False)
    participant_code = Column(String, nullable=False)
    consent_form_id = Column(String, nullable=False)
    consent_date = Column(Date, nullable=False)
    withdrawn_on = Column(Date, nullable=True)
    created_on = Column(Date, nullable=False, server_default=text("current_date"))

    __table_args__ = (
        UniqueConstraint("site_id", "participant_code", name="participant_site_id_participant_code_key"),
        Index("idx_participant_site", "site_id"),
    )


class ParticipantAlias(Base):
    __tablename__ = "participant_alias"

    alias_id = Column(UUID(as_uuid=True), primary_key=True)
    participant_id = Column(UUID(as_uuid=True), ForeignKey("participant.id"), nullable=False)
    device_id = Column(UUID(as_uuid=True), ForeignKey("device.id"), nullable=False)
    created_on = Column(Date, nullable=False, server_default=text("current_date"))

    __table_args__ = (
        Index("idx_alias_participant", "participant_id"),
    )


class Session(Base):
    __tablename__ = "session"

    id = Column(UUID(as_uuid=True), primary_key=True)
    participant_id = Column(UUID(as_uuid=True), ForeignKey("participant.id"), nullable=False)
    device_id = Column(UUID(as_uuid=True), ForeignKey("device.id"), nullable=False)
    card_status = Column(String, nullable=False)
    card_batch_id = Column(String, ForeignKey("card_batch.batch_id"), nullable=True)
    capture_date = Column(Date, nullable=False)
    captured_at = Column(DateTime(timezone=True), nullable=True)
    age_hours = Column(Numeric(5, 1), nullable=False)
    gestational_age_weeks = Column(SmallInteger, nullable=False)
    flag_jaundice_under_24h = Column(Boolean, nullable=False)
    flag_danger_signs = Column(Boolean, nullable=False)
    flag_prior_phototherapy = Column(Boolean, nullable=False)
    estimate_mg_dl = Column(Numeric(4, 1), nullable=True)
    estimate_low_mg_dl = Column(Numeric(4, 1), nullable=True)
    estimate_high_mg_dl = Column(Numeric(4, 1), nullable=True)
    app_version = Column(String, nullable=False)
    model_version = Column(String, nullable=True)
    config_version = Column(String, nullable=False)
    manifest_sha256 = Column(String, nullable=False)
    status = Column(String, nullable=False, server_default=text("'RECEIVING'"))
    rejected_reason = Column(String, nullable=True)
    received_on = Column(Date, nullable=False, server_default=text("current_date"))
    completed_on = Column(Date, nullable=True)

    __table_args__ = (
        CheckConstraint("card_status IN ('VERIFIED','EXPIRED','UNVERIFIED')", name="session_card_status_check"),
        CheckConstraint("age_hours BETWEEN 0 AND 720", name="session_age_hours_check"),
        CheckConstraint("gestational_age_weeks BETWEEN 22 AND 44", name="session_gestational_age_weeks_check"),
        CheckConstraint("status IN ('RECEIVING','COMPLETE','REJECTED')", name="session_status_check"),
        CheckConstraint("rejected_reason IN ('CONSENT_WITHDRAWN','DATA_ERROR')", name="session_rejected_reason_check"),
        CheckConstraint("((card_status = 'UNVERIFIED') = (card_batch_id IS NULL))", name="session_card_status_batch_check"),
        CheckConstraint("((status = 'REJECTED') = (rejected_reason IS NOT NULL))", name="session_status_rejected_reason_check"),
        Index("idx_session_participant", "participant_id"),
        Index("idx_session_device_date", "device_id", "capture_date"),
        Index("idx_session_status", "status", postgresql_where=text("status <> 'COMPLETE'")),
        Index("idx_session_batch", "card_batch_id"),
    )


class Capture(Base):
    __tablename__ = "capture"

    id = Column(UUID(as_uuid=True), primary_key=True)
    session_id = Column(UUID(as_uuid=True), ForeignKey("session.id", ondelete="CASCADE"), nullable=False)
    attempt_number = Column(SmallInteger, nullable=False)
    site = Column(String, nullable=False)
    capture_metadata = Column(JSONB, nullable=False)
    quality_status = Column(String, nullable=False)
    quality_fail_reasons = Column(JSONB, nullable=True)
    correction_residual_de = Column(Numeric(5, 2), nullable=True)
    features = Column(JSONB, nullable=True)
    has_image = Column(Boolean, nullable=False)
    object_key = Column(String, nullable=True)
    sha256 = Column(String, nullable=True)
    size_bytes = Column(Integer, nullable=True)
    verified = Column(Boolean, nullable=False, server_default=text("false"))

    __table_args__ = (
        CheckConstraint("attempt_number BETWEEN 1 AND 3", name="capture_attempt_number_check"),
        CheckConstraint(
            "site IN ('STERNUM','FOREHEAD','SCLERA_FLASH','SCLERA_NOFLASH','CALIBRATION')",
            name="capture_site_check",
        ),
        CheckConstraint("quality_status IN ('PASSED','FAILED')", name="capture_quality_status_check"),
        CheckConstraint("size_bytes > 0 AND size_bytes <= 20000000", name="capture_size_bytes_check"),
        CheckConstraint("has_image = (sha256 IS NOT NULL)", name="capture_has_image_sha256_check"),
        CheckConstraint("has_image = (size_bytes IS NOT NULL)", name="capture_has_image_size_bytes_check"),
        CheckConstraint("has_image OR NOT verified", name="capture_has_image_verified_check"),
        Index("idx_capture_session", "session_id"),
        Index("idx_capture_unverified", "session_id", postgresql_where=text("has_image AND NOT verified")),
    )


class TsbMeasurement(Base):
    __tablename__ = "tsb_measurement"

    id = Column(UUID(as_uuid=True), primary_key=True)
    participant_id = Column(UUID(as_uuid=True), ForeignKey("participant.id"), nullable=False)
    session_id = Column(UUID(as_uuid=True), ForeignKey("session.id"), nullable=True)
    tsb_mg_dl = Column(Numeric(4, 1), nullable=False)
    minutes_from_capture = Column(Integer, nullable=True)
    draw_time = Column(DateTime(timezone=True), nullable=True)
    method = Column(String, nullable=False)
    received_on = Column(Date, nullable=False, server_default=text("current_date"))
    linked_on = Column(Date, nullable=True)

    __table_args__ = (
        CheckConstraint("tsb_mg_dl > 0 AND tsb_mg_dl <= 40", name="tsb_measurement_tsb_mg_dl_check"),
        CheckConstraint("minutes_from_capture BETWEEN -10080 AND 10080", name="tsb_measurement_minutes_from_capture_check"),
        CheckConstraint("method IN ('LAB','POINT_OF_CARE','OTHER')", name="tsb_measurement_method_check"),
        Index("idx_tsb_participant", "participant_id"),
        Index("idx_tsb_session", "session_id"),
    )


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_record"

    device_id = Column(UUID(as_uuid=True), ForeignKey("device.id"), primary_key=True)
    idempotency_key = Column(UUID(as_uuid=True), primary_key=True)
    request_sha256 = Column(String, nullable=False)
    state = Column(String, nullable=False)
    response_status = Column(SmallInteger, nullable=True)
    response_body = Column(JSONB, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))

    __table_args__ = (
        CheckConstraint("state IN ('IN_PROGRESS','DONE')", name="idempotency_record_state_check"),
        CheckConstraint("((state = 'DONE') = (response_status IS NOT NULL))", name="idempotency_record_done_status_check"),
        Index("idx_idempotency_created", "created_at"),
    )


class AuditLog(Base):
    __tablename__ = "audit_log"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    occurred_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))
    actor_type = Column(String, nullable=False)
    actor_id = Column(String, nullable=False)
    action = Column(String, nullable=False)
    target_type = Column(String, nullable=True)
    target_id = Column(String, nullable=True)
    details = Column(JSONB, nullable=True)

    __table_args__ = (
        CheckConstraint("actor_type IN ('DEVICE','ADMIN','SYSTEM')", name="audit_log_actor_type_check"),
        Index("idx_audit_time", "occurred_at"),
    )


class ExportJob(Base):
    __tablename__ = "export_job"

    id = Column(UUID(as_uuid=True), primary_key=True, server_default=text("gen_random_uuid()"))
    study_id = Column(UUID(as_uuid=True), ForeignKey("study.id"), nullable=False)
    requested_by = Column(String, nullable=False)
    filters = Column(JSONB, nullable=True)
    status = Column(String, nullable=False, server_default=text("'PENDING'"))
    output_path = Column(String, nullable=True)
    output_sha256 = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=text("now()"))
    finished_at = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        CheckConstraint("status IN ('PENDING','RUNNING','DONE','FAILED')", name="export_job_status_check"),
    )
