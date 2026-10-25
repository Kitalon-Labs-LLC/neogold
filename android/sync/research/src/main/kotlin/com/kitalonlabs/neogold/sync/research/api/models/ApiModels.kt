package com.kitalonlabs.neogold.sync.research.api.models

import kotlinx.serialization.Serializable

@Serializable
data class Health(
    val status: String,
    val api_version: String,
    val server_time: String
)

@Serializable
data class Jwk(
    val kty: String = "EC",
    val crv: String = "P-256",
    val x: String,
    val y: String
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val android_api: Int,
    val app_version: String,
    val camera_hardware_level: String,
    val qualification: String
)

@Serializable
data class EnrollmentRequest(
    val enrollment_code: String,
    val device_public_key: Jwk,
    val key_attestation_chain: List<String>? = null,
    val device: DeviceInfo
)

@Serializable
data class UploadPolicy(
    val wifi_only: Boolean = true,
    val max_image_bytes: Long = 5000000L,
    val max_requests_per_minute: Int = 60,
    val timestamp_precision: String = "DAY_PLUS_OFFSETS",
    val time_zone: String = "Asia/Dhaka"
)

@Serializable
data class EnrollmentResponse(
    val data_generation: Long = 1L,
    val device_id: String,
    val study_code: String,
    val study_name: String = "",
    val site_code: String = "",
    val server_time: String = "",
    val upload_policy: UploadPolicy = UploadPolicy()
)

@Serializable
data class DeviceStatus(
    val data_generation: Long = 1L,
    val device_id: String,
    val site_code: String = "DHK-01",
    val study_status: String = "ACTIVE",
    val revoked: Boolean = false,
    val server_time: String = "2026-10-10T08:15:00Z",
    val min_app_version: String = "1.0.0",
    val upload_policy: UploadPolicy = UploadPolicy()
)

@Serializable
data class CardBatchUpload(
    val qr_payload: String
)

@Serializable
data class CardBatchReceipt(
    val batch_id: String,
    val issuer_id: String = "K-01",
    val signature_valid: Boolean = true,
    val expires_at: String = "2027-09-30"
)

@Serializable
data class ParticipantUpload(
    val participant_code: String,
    val consent_form_id: String,
    val consent_date: String
)

@Serializable
data class ParticipantReceipt(
    val participant_id: String,
    val canonical_participant_id: String = participant_id,
    val site_code: String = "DHK-01",
    val created_on: String = "2026-10-10"
)

@Serializable
data class CaptureMetadata(
    val iso: Int? = null,
    val exposure_ns: Long? = null,
    val ae_locked: Boolean = true,
    val awb_locked: Boolean = true,
    val camera_hardware_level: String = "LIMITED",
    val color_gains: List<Double>? = null
)

@Serializable
data class CaptureManifest(
    val capture_id: String,
    val attempt_number: Int,
    val site: String,
    val quality_status: String,
    val quality_fail_reasons: List<String>? = null,
    val correction_residual_de: Double? = null,
    val features: Map<String, Double>? = null,
    val capture_metadata: CaptureMetadata,
    val has_image: Boolean,
    val image_sha256: String? = null,
    val size_bytes: Long? = null
)

@Serializable
data class SessionManifest(
    val participant_id: String,
    val card_status: String,
    val card_batch_id: String? = null,
    val capture_date: String,
    val captured_at: String? = null,
    val age_hours: Double,
    val gestational_age_weeks: Int,
    val flag_jaundice_under_24h: Boolean,
    val flag_danger_signs: Boolean,
    val flag_prior_phototherapy: Boolean,
    val estimate_mg_dl: Double? = null,
    val estimate_low_mg_dl: Double? = null,
    val estimate_high_mg_dl: Double? = null,
    val app_version: String,
    val model_version: String? = null,
    val config_version: String,
    val captures: List<CaptureManifest>
)

@Serializable
data class SessionStatus(
    val session_id: String,
    val status: String = "RECEIVING",
    val missing_images: List<String>? = null,
    val received_on: String = "2026-10-10",
    val completed_on: String? = null
)

@Serializable
data class ImageReceipt(
    val session_status: String = "RECEIVING",
    val missing_count: Int = 0,
    val capture_id: String,
    val sha256: String = "",
    val size_bytes: Long = 0L,
    val verified: Boolean = true
)

@Serializable
data class TsbUpload(
    val participant_id: String,
    val session_id: String? = null,
    val tsb_mg_dl: Double,
    val minutes_from_capture: Int? = null,
    val draw_time: String? = null,
    val method: String
)

@Serializable
data class TsbReceipt(
    val tsb_id: String = "",
    val participant_id: String = "",
    val linked: Boolean = true,
    val received_on: String = "2026-10-10"
)

@Serializable
data class Problem(
    val type: String = "https://neogold.example/problems/error",
    val title: String = "Error",
    val status: Int = 500,
    val code: String = "SERVER_ERROR",
    val retryable: Boolean = false,
    val request_id: String = "unknown",
    val detail: String? = null,
    val instance: String? = null,
    val errors: List<FieldError>? = null,
    val server_time: String? = null,
    val min_app_version: String? = null
)

@Serializable
data class FieldError(
    val field: String,
    val message: String
)
