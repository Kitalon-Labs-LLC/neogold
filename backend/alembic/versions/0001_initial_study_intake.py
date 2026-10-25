"""Initial study intake schema reproducing doc 5 Section 3 DDL.

Revision ID: 0001
Revises:
Create Date: 2026-10-20 12:00:00.000000

"""
from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1. Study and sites
    op.create_table(
        "study",
        sa.Column("id", postgresql.UUID(as_uuid=True), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("study_code", sa.Text(), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("partner_institution", sa.Text(), nullable=False),
        sa.Column("ethics_reference", sa.Text(), nullable=False),
        sa.Column("timestamp_precision", sa.Text(), server_default=sa.text("'DAY_PLUS_OFFSETS'"), nullable=False),
        sa.Column("retention_until", sa.Date(), nullable=False),
        sa.Column("time_zone", sa.Text(), server_default=sa.text("'Asia/Dhaka'"), nullable=False),
        sa.Column("min_app_version", sa.Text(), server_default=sa.text("'1.0.0'"), nullable=False),
        sa.Column("wifi_only", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("max_image_bytes", sa.Integer(), server_default=sa.text("20000000"), nullable=False),
        sa.Column("max_requests_per_minute", sa.Integer(), server_default=sa.text("60"), nullable=False),
        sa.Column("data_generation", sa.BigInteger(), server_default=sa.text("1"), nullable=False),
        sa.Column("status", sa.Text(), server_default=sa.text("'ACTIVE'"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("timestamp_precision IN ('DAY_PLUS_OFFSETS','FULL')", name="study_timestamp_precision_check"),
        sa.CheckConstraint("max_image_bytes BETWEEN 1 AND 20000000", name="study_max_image_bytes_check"),
        sa.CheckConstraint("status IN ('ACTIVE','PAUSED','CLOSED')", name="study_status_check"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("study_code"),
    )

    op.create_table(
        "site",
        sa.Column("id", postgresql.UUID(as_uuid=True), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("study_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("site_code", sa.Text(), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("is_test", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["study_id"], ["study.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("study_id", "site_code"),
    )

    # 2. Devices and enrollment
    op.create_table(
        "device",
        sa.Column("id", postgresql.UUID(as_uuid=True), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("public_key", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("key_thumbprint", sa.Text(), nullable=False),
        sa.Column("key_algorithm", sa.Text(), nullable=False),
        sa.Column("attestation", sa.Text(), server_default=sa.text("'NONE'"), nullable=False),
        sa.Column("manufacturer", sa.Text(), nullable=False),
        sa.Column("device_model", sa.Text(), nullable=False),
        sa.Column("android_api", sa.SmallInteger(), nullable=False),
        sa.Column("camera_hardware_level", sa.Text(), nullable=False),
        sa.Column("qualification", sa.Text(), nullable=False),
        sa.Column("app_version", sa.Text(), nullable=False),
        sa.Column("enrolled_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("last_seen_on", sa.Date(), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_reason", sa.Text(), nullable=True),
        sa.CheckConstraint("key_algorithm IN ('ES256')", name="device_key_algorithm_check"),
        sa.CheckConstraint("attestation IN ('NONE','VERIFIED','FAILED')", name="device_attestation_check"),
        sa.CheckConstraint("android_api >= 26", name="device_android_api_check"),
        sa.CheckConstraint("qualification IN ('QUALIFIED','RESEARCH_ONLY')", name="device_qualification_check"),
        sa.CheckConstraint("revoked_reason IN ('LOST','STOLEN','REASSIGNED','SELF_UNENROLLED','COMPROMISED','STUDY_ENDED')", name="device_revoked_reason_check"),
        sa.ForeignKeyConstraint(["site_id"], ["site.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("key_thumbprint"),
    )

    op.create_table(
        "enrollment_code",
        sa.Column("id", postgresql.UUID(as_uuid=True), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("study_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("code_hash", sa.Text(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("enroll_idempotency_key", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("enroll_key_thumbprint", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["device.id"], name="enrollment_code_device_fk"),
        sa.ForeignKeyConstraint(["site_id"], ["site.id"]),
        sa.ForeignKeyConstraint(["study_id"], ["study.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("code_hash"),
    )

    op.create_table(
        "auth_nonce",
        sa.Column("jti", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["device.id"]),
        sa.PrimaryKeyConstraint("jti"),
    )

    # 3. Card batches
    op.create_table(
        "card_batch",
        sa.Column("batch_id", sa.Text(), nullable=False),
        sa.Column("issuer_id", sa.Text(), nullable=False),
        sa.Column("qr_payload", sa.LargeBinary(), nullable=False),
        sa.Column("issuer_key_id", sa.Text(), nullable=False),
        sa.Column("reference_values", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("issued_at", sa.Date(), nullable=False),
        sa.Column("expires_at", sa.Date(), nullable=False),
        sa.Column("first_seen_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("batch_id"),
    )

    # 4. Participants
    op.create_table(
        "participant",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("participant_code", sa.Text(), nullable=False),
        sa.Column("consent_form_id", sa.Text(), nullable=False),
        sa.Column("consent_date", sa.Date(), nullable=False),
        sa.Column("withdrawn_on", sa.Date(), nullable=True),
        sa.Column("created_on", sa.Date(), server_default=sa.text("current_date"), nullable=False),
        sa.ForeignKeyConstraint(["site_id"], ["site.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("site_id", "participant_code"),
    )

    op.create_table(
        "participant_alias",
        sa.Column("alias_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("participant_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_on", sa.Date(), server_default=sa.text("current_date"), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["device.id"]),
        sa.ForeignKeyConstraint(["participant_id"], ["participant.id"]),
        sa.PrimaryKeyConstraint("alias_id"),
    )

    # 5. Research sessions
    op.create_table(
        "session",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("participant_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("card_status", sa.Text(), nullable=False),
        sa.Column("card_batch_id", sa.Text(), nullable=True),
        sa.Column("capture_date", sa.Date(), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("age_hours", sa.Numeric(precision=5, scale=1), nullable=False),
        sa.Column("gestational_age_weeks", sa.SmallInteger(), nullable=False),
        sa.Column("flag_jaundice_under_24h", sa.Boolean(), nullable=False),
        sa.Column("flag_danger_signs", sa.Boolean(), nullable=False),
        sa.Column("flag_prior_phototherapy", sa.Boolean(), nullable=False),
        sa.Column("estimate_mg_dl", sa.Numeric(precision=4, scale=1), nullable=True),
        sa.Column("estimate_low_mg_dl", sa.Numeric(precision=4, scale=1), nullable=True),
        sa.Column("estimate_high_mg_dl", sa.Numeric(precision=4, scale=1), nullable=True),
        sa.Column("app_version", sa.Text(), nullable=False),
        sa.Column("model_version", sa.Text(), nullable=True),
        sa.Column("config_version", sa.Text(), nullable=False),
        sa.Column("manifest_sha256", sa.Text(), nullable=False),
        sa.Column("status", sa.Text(), server_default=sa.text("'RECEIVING'"), nullable=False),
        sa.Column("rejected_reason", sa.Text(), nullable=True),
        sa.Column("received_on", sa.Date(), server_default=sa.text("current_date"), nullable=False),
        sa.Column("completed_on", sa.Date(), nullable=True),
        sa.CheckConstraint("card_status IN ('VERIFIED','EXPIRED','UNVERIFIED')", name="session_card_status_check"),
        sa.CheckConstraint("age_hours BETWEEN 0 AND 720", name="session_age_hours_check"),
        sa.CheckConstraint("gestational_age_weeks BETWEEN 22 AND 44", name="session_gestational_age_weeks_check"),
        sa.CheckConstraint("status IN ('RECEIVING','COMPLETE','REJECTED')", name="session_status_check"),
        sa.CheckConstraint("rejected_reason IN ('CONSENT_WITHDRAWN','DATA_ERROR')", name="session_rejected_reason_check"),
        sa.CheckConstraint("((card_status = 'UNVERIFIED') = (card_batch_id IS NULL))", name="session_card_status_batch_check"),
        sa.CheckConstraint("((status = 'REJECTED') = (rejected_reason IS NOT NULL))", name="session_status_rejected_reason_check"),
        sa.ForeignKeyConstraint(["card_batch_id"], ["card_batch.batch_id"]),
        sa.ForeignKeyConstraint(["device_id"], ["device.id"]),
        sa.ForeignKeyConstraint(["participant_id"], ["participant.id"]),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_table(
        "capture",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("attempt_number", sa.SmallInteger(), nullable=False),
        sa.Column("site", sa.Text(), nullable=False),
        sa.Column("capture_metadata", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("quality_status", sa.Text(), nullable=False),
        sa.Column("quality_fail_reasons", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("correction_residual_de", sa.Numeric(precision=5, scale=2), nullable=True),
        sa.Column("features", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("has_image", sa.Boolean(), nullable=False),
        sa.Column("object_key", sa.Text(), nullable=True),
        sa.Column("sha256", sa.Text(), nullable=True),
        sa.Column("size_bytes", sa.Integer(), nullable=True),
        sa.Column("verified", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.CheckConstraint("attempt_number BETWEEN 1 AND 3", name="capture_attempt_number_check"),
        sa.CheckConstraint("site IN ('STERNUM','FOREHEAD','SCLERA_FLASH','SCLERA_NOFLASH','CALIBRATION')", name="capture_site_check"),
        sa.CheckConstraint("quality_status IN ('PASSED','FAILED')", name="capture_quality_status_check"),
        sa.CheckConstraint("size_bytes > 0 AND size_bytes <= 20000000", name="capture_size_bytes_check"),
        sa.CheckConstraint("has_image = (sha256 IS NOT NULL)", name="capture_has_image_sha256_check"),
        sa.CheckConstraint("has_image = (size_bytes IS NOT NULL)", name="capture_has_image_size_bytes_check"),
        sa.CheckConstraint("has_image OR NOT verified", name="capture_has_image_verified_check"),
        sa.ForeignKeyConstraint(["session_id"], ["session.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_table(
        "tsb_measurement",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("participant_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("tsb_mg_dl", sa.Numeric(precision=4, scale=1), nullable=False),
        sa.Column("minutes_from_capture", sa.Integer(), nullable=True),
        sa.Column("draw_time", sa.DateTime(timezone=True), nullable=True),
        sa.Column("method", sa.Text(), nullable=False),
        sa.Column("received_on", sa.Date(), server_default=sa.text("current_date"), nullable=False),
        sa.Column("linked_on", sa.Date(), nullable=True),
        sa.CheckConstraint("tsb_mg_dl > 0 AND tsb_mg_dl <= 40", name="tsb_measurement_tsb_mg_dl_check"),
        sa.CheckConstraint("minutes_from_capture BETWEEN -10080 AND 10080", name="tsb_measurement_minutes_from_capture_check"),
        sa.CheckConstraint("method IN ('LAB','POINT_OF_CARE','OTHER')", name="tsb_measurement_method_check"),
        sa.ForeignKeyConstraint(["participant_id"], ["participant.id"]),
        sa.ForeignKeyConstraint(["session_id"], ["session.id"]),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_table(
        "idempotency_record",
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("idempotency_key", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_sha256", sa.Text(), nullable=False),
        sa.Column("state", sa.Text(), nullable=False),
        sa.Column("response_status", sa.SmallInteger(), nullable=True),
        sa.Column("response_body", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("state IN ('IN_PROGRESS','DONE')", name="idempotency_record_state_check"),
        sa.CheckConstraint("((state = 'DONE') = (response_status IS NOT NULL))", name="idempotency_record_done_status_check"),
        sa.ForeignKeyConstraint(["device_id"], ["device.id"]),
        sa.PrimaryKeyConstraint("device_id", "idempotency_key"),
    )

    op.create_table(
        "audit_log",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("actor_type", sa.Text(), nullable=False),
        sa.Column("actor_id", sa.Text(), nullable=False),
        sa.Column("action", sa.Text(), nullable=False),
        sa.Column("target_type", sa.Text(), nullable=True),
        sa.Column("target_id", sa.Text(), nullable=True),
        sa.Column("details", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.CheckConstraint("actor_type IN ('DEVICE','ADMIN','SYSTEM')", name="audit_log_actor_type_check"),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_table(
        "export_job",
        sa.Column("id", postgresql.UUID(as_uuid=True), server_default=sa.text("gen_random_uuid()"), nullable=False),
        sa.Column("study_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("requested_by", sa.Text(), nullable=False),
        sa.Column("filters", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("status", sa.Text(), server_default=sa.text("'PENDING'"), nullable=False),
        sa.Column("output_path", sa.Text(), nullable=True),
        sa.Column("output_sha256", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("status IN ('PENDING','RUNNING','DONE','FAILED')", name="export_job_status_check"),
        sa.ForeignKeyConstraint(["study_id"], ["study.id"]),
        sa.PrimaryKeyConstraint("id"),
    )

    # 6. Indexes matching doc 5 Section 3.3
    op.create_index("idx_device_site", "device", ["site_id"], postgresql_where=sa.text("revoked_at IS NULL"))
    op.create_index("idx_participant_site", "participant", ["site_id"])
    op.create_index("idx_session_participant", "session", ["participant_id"])
    op.create_index("idx_session_device_date", "session", ["device_id", "capture_date"])
    op.create_index("idx_session_status", "session", ["status"], postgresql_where=sa.text("status <> 'COMPLETE'"))
    op.create_index("idx_session_batch", "session", ["card_batch_id"])
    op.create_index("idx_capture_session", "capture", ["session_id"])
    op.create_index("idx_capture_unverified", "capture", ["session_id"], postgresql_where=sa.text("has_image AND NOT verified"))
    op.create_index("idx_alias_participant", "participant_alias", ["participant_id"])
    op.create_index("idx_tsb_participant", "tsb_measurement", ["participant_id"])
    op.create_index("idx_tsb_session", "tsb_measurement", ["session_id"])
    op.create_index("idx_idempotency_created", "idempotency_record", ["created_at"])
    op.create_index("idx_auth_nonce_expiry", "auth_nonce", ["expires_at"])
    op.create_index("idx_audit_time", "audit_log", ["occurred_at"])


def downgrade() -> None:
    op.drop_index("idx_audit_time", table_name="audit_log")
    op.drop_index("idx_auth_nonce_expiry", table_name="auth_nonce")
    op.drop_index("idx_idempotency_created", table_name="idempotency_record")
    op.drop_index("idx_tsb_session", table_name="tsb_measurement")
    op.drop_index("idx_tsb_participant", table_name="tsb_measurement")
    op.drop_index("idx_alias_participant", table_name="participant_alias")
    op.drop_index("idx_capture_unverified", table_name="capture")
    op.drop_index("idx_capture_session", table_name="capture")
    op.drop_index("idx_session_batch", table_name="session")
    op.drop_index("idx_session_status", table_name="session")
    op.drop_index("idx_session_device_date", table_name="session")
    op.drop_index("idx_session_participant", table_name="session")
    op.drop_index("idx_participant_site", table_name="participant")
    op.drop_index("idx_device_site", table_name="device")

    op.drop_table("export_job")
    op.drop_table("audit_log")
    op.drop_table("idempotency_record")
    op.drop_table("tsb_measurement")
    op.drop_table("capture")
    op.drop_table("session")
    op.drop_table("participant_alias")
    op.drop_table("participant")
    op.drop_table("card_batch")
    op.drop_table("auth_nonce")
    op.drop_table("enrollment_code")
    op.drop_table("device")
    op.drop_table("site")
    op.drop_table("study")
