package com.kitalonlabs.neogold.sync.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.sync.research.api.models.CaptureManifest
import com.kitalonlabs.neogold.sync.research.api.models.CaptureMetadata
import com.kitalonlabs.neogold.sync.research.api.models.CardBatchReceipt
import com.kitalonlabs.neogold.sync.research.api.models.CardBatchUpload
import com.kitalonlabs.neogold.sync.research.api.models.DeviceInfo
import com.kitalonlabs.neogold.sync.research.api.models.DeviceStatus
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentRequest
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentResponse
import com.kitalonlabs.neogold.sync.research.api.models.Health
import com.kitalonlabs.neogold.sync.research.api.models.ImageReceipt
import com.kitalonlabs.neogold.sync.research.api.models.Jwk
import com.kitalonlabs.neogold.sync.research.api.models.ParticipantReceipt
import com.kitalonlabs.neogold.sync.research.api.models.ParticipantUpload
import com.kitalonlabs.neogold.sync.research.api.models.Problem
import com.kitalonlabs.neogold.sync.research.api.models.SessionManifest
import com.kitalonlabs.neogold.sync.research.api.models.SessionStatus
import com.kitalonlabs.neogold.sync.research.api.models.TsbReceipt
import com.kitalonlabs.neogold.sync.research.api.models.TsbUpload
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Validates request models and response models against openapi.yaml schemas and examples (Build Item 7).
 */
class OpenApiContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun healthResponse_deserializesSpecExample() {
        val sample = """
            {
              "status": "ok",
              "api_version": "1.0.0",
              "server_time": "2026-10-10T08:15:00Z"
            }
        """.trimIndent()

        val health = json.decodeFromString(Health.serializer(), sample)
        assertThat(health.status).isEqualTo("ok")
        assertThat(health.api_version).isEqualTo("1.0.0")
        assertThat(health.server_time).isEqualTo("2026-10-10T08:15:00Z")
    }

    @Test
    fun enrollmentRequest_serializesAndMatchesSpec() {
        val req = EnrollmentRequest(
            enrollment_code = "K7QM-3XRA-9TPD",
            device_public_key = Jwk(
                kty = "EC",
                crv = "P-256",
                x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"
            ),
            device = DeviceInfo(
                manufacturer = "TECNO",
                model = "TECNO KL8",
                android_api = 34,
                app_version = "1.0.0",
                camera_hardware_level = "LIMITED",
                qualification = "QUALIFIED"
            )
        )
        val serialized = json.encodeToString(EnrollmentRequest.serializer(), req)
        val decoded = json.decodeFromString(EnrollmentRequest.serializer(), serialized)
        assertThat(decoded.enrollment_code).isEqualTo("K7QM-3XRA-9TPD")
        assertThat(decoded.device.manufacturer).isEqualTo("TECNO")
        assertThat(decoded.device_public_key.x).isEqualTo("f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU")
    }

    @Test
    fun enrollmentResponse_deserializesSpecExample() {
        val sample = """
            {
              "data_generation": 1,
              "device_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "study_code": "SKP-2026",
              "study_name": "Sakhipur Rural Neonatal Screening Study",
              "site_code": "DHK-01",
              "server_time": "2026-10-10T08:15:30Z",
              "upload_policy": {
                "wifi_only": true,
                "max_image_bytes": 5000000,
                "max_requests_per_minute": 60,
                "timestamp_precision": "DAY_PLUS_OFFSETS",
                "time_zone": "Asia/Dhaka"
              }
            }
        """.trimIndent()

        val resp = json.decodeFromString(EnrollmentResponse.serializer(), sample)
        assertThat(resp.device_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(resp.study_code).isEqualTo("SKP-2026")
        assertThat(resp.site_code).isEqualTo("DHK-01")
        assertThat(resp.upload_policy.timestamp_precision).isEqualTo("DAY_PLUS_OFFSETS")
        assertThat(resp.upload_policy.time_zone).isEqualTo("Asia/Dhaka")
        assertThat(resp.upload_policy.wifi_only).isTrue()
    }

    @Test
    fun deviceStatus_deserializesSpecExample() {
        val sample = """
            {
              "data_generation": 4,
              "device_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "site_code": "DHK-01",
              "study_status": "ACTIVE",
              "revoked": false,
              "server_time": "2026-10-10T08:15:00Z",
              "min_app_version": "1.0.0",
              "upload_policy": {
                "wifi_only": true,
                "max_image_bytes": 5000000,
                "max_requests_per_minute": 60,
                "timestamp_precision": "DAY_PLUS_OFFSETS",
                "time_zone": "Asia/Dhaka"
              }
            }
        """.trimIndent()

        val status = json.decodeFromString(DeviceStatus.serializer(), sample)
        assertThat(status.device_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(status.study_status).isEqualTo("ACTIVE")
        assertThat(status.revoked).isFalse()
        assertThat(status.data_generation).isEqualTo(4L)
        assertThat(status.min_app_version).isEqualTo("1.0.0")
    }

    @Test
    fun cardBatch_serializesAndDeserializesSpecExample() {
        val sample = """
            {
              "batch_id": "B-2026-09-001",
              "issuer_id": "k-01-brac",
              "signature_valid": true,
              "expires_at": "2027-09-30"
            }
        """.trimIndent()

        val receipt = json.decodeFromString(CardBatchReceipt.serializer(), sample)
        assertThat(receipt.batch_id).isEqualTo("B-2026-09-001")
        assertThat(receipt.issuer_id).isEqualTo("k-01-brac")
        assertThat(receipt.signature_valid).isTrue()
        assertThat(receipt.expires_at).isEqualTo("2027-09-30")

        val upload = CardBatchUpload(qr_payload = "QUJDREVGR0g=")
        val uploadJson = json.encodeToString(CardBatchUpload.serializer(), upload)
        val decodedUpload = json.decodeFromString(CardBatchUpload.serializer(), uploadJson)
        assertThat(decodedUpload.qr_payload).isEqualTo("QUJDREVGR0g=")
    }

    @Test
    fun participant_serializesAndDeserializesSpecExample() {
        val sample = """
            {
              "participant_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "canonical_participant_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "site_code": "DHK-01",
              "created_on": "2026-10-10"
            }
        """.trimIndent()

        val receipt = json.decodeFromString(ParticipantReceipt.serializer(), sample)
        assertThat(receipt.participant_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(receipt.canonical_participant_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(receipt.site_code).isEqualTo("DHK-01")
        assertThat(receipt.created_on).isEqualTo("2026-10-10")

        val upload = ParticipantUpload(
            participant_code = "SKP-0142",
            consent_form_id = "CF-2026-001",
            consent_date = "2026-10-10"
        )
        val uploadJson = json.encodeToString(ParticipantUpload.serializer(), upload)
        val decodedUpload = json.decodeFromString(ParticipantUpload.serializer(), uploadJson)
        assertThat(decodedUpload.participant_code).isEqualTo("SKP-0142")
    }

    @Test
    fun sessionStatus_deserializesSpecExample() {
        val sample = """
            {
              "session_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "status": "RECEIVING",
              "missing_images": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
              "received_on": "2026-10-10",
              "completed_on": null
            }
        """.trimIndent()

        val status = json.decodeFromString(SessionStatus.serializer(), sample)
        assertThat(status.session_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(status.status).isEqualTo("RECEIVING")
        assertThat(status.missing_images).containsExactly("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(status.received_on).isEqualTo("2026-10-10")
    }

    @Test
    fun sessionManifest_serializesAndMatchesSpec() {
        val manifest = SessionManifest(
            participant_id = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            card_status = "VERIFIED",
            card_batch_id = "B-2026-09-001",
            capture_date = "2026-10-10",
            age_hours = 48.0,
            gestational_age_weeks = 38,
            flag_jaundice_under_24h = false,
            flag_danger_signs = false,
            flag_prior_phototherapy = false,
            estimate_mg_dl = 12.5,
            estimate_low_mg_dl = 11.2,
            estimate_high_mg_dl = 13.8,
            app_version = "1.0.0",
            model_version = "1.0.0",
            config_version = "1.0.0",
            captures = listOf(
                CaptureManifest(
                    capture_id = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    attempt_number = 1,
                    site = "STERNUM",
                    quality_status = "PASSED",
                    has_image = true,
                    image_sha256 = "3a7bd20a84d31f0e4792b95be2488a1e8a4a5b6c7d8e9f0123456789abcdef01",
                    size_bytes = 245180L,
                    capture_metadata = CaptureMetadata(
                        ae_locked = true,
                        awb_locked = true,
                        camera_hardware_level = "LIMITED"
                    )
                )
            )
        )
        val serialized = json.encodeToString(SessionManifest.serializer(), manifest)
        val decoded = json.decodeFromString(SessionManifest.serializer(), serialized)
        assertThat(decoded.participant_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(decoded.captures).hasSize(1)
        assertThat(decoded.captures[0].quality_status).isEqualTo("PASSED")
    }

    @Test
    fun imageReceipt_deserializesSpecExample() {
        val sample = """
            {
              "session_status": "RECEIVING",
              "missing_count": 5,
              "capture_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "sha256": "3a7bd20a84d31f0e4792b95be2488a1e8a4a5b6c7d8e9f0123456789abcdef01",
              "size_bytes": 245180,
              "verified": true
            }
        """.trimIndent()

        val receipt = json.decodeFromString(ImageReceipt.serializer(), sample)
        assertThat(receipt.capture_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(receipt.sha256).isEqualTo("3a7bd20a84d31f0e4792b95be2488a1e8a4a5b6c7d8e9f0123456789abcdef01")
        assertThat(receipt.size_bytes).isEqualTo(245180L)
        assertThat(receipt.verified).isTrue()
        assertThat(receipt.missing_count).isEqualTo(5)
    }

    @Test
    fun tsbReceipt_deserializesSpecExample() {
        val sample = """
            {
              "tsb_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "participant_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "linked": true,
              "received_on": "2026-10-10"
            }
        """.trimIndent()

        val receipt = json.decodeFromString(TsbReceipt.serializer(), sample)
        assertThat(receipt.tsb_id).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertThat(receipt.linked).isTrue()
        assertThat(receipt.received_on).isEqualTo("2026-10-10")

        val upload = TsbUpload(
            participant_id = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            tsb_mg_dl = 13.5,
            method = "LAB"
        )
        val uploadJson = json.encodeToString(TsbUpload.serializer(), upload)
        val decodedUpload = json.decodeFromString(TsbUpload.serializer(), uploadJson)
        assertThat(decodedUpload.tsb_mg_dl).isEqualTo(13.5)
    }

    @Test
    fun problemDetails_deserializesRfc9457WithCode() {
        val sample = """
            {
              "type": "https://neogold.example/problems/clock-skew",
              "title": "Device clock is out of range",
              "status": 401,
              "code": "CLOCK_SKEW",
              "retryable": true,
              "server_time": "2026-10-10T08:15:00Z",
              "request_id": "01J9Z6Q4V8M3N2P1R0S9T8U7V6"
            }
        """.trimIndent()

        val problem = json.decodeFromString(Problem.serializer(), sample)
        assertThat(problem.status).isEqualTo(401)
        assertThat(problem.code).isEqualTo("CLOCK_SKEW")
        assertThat(problem.retryable).isTrue()
        assertThat(problem.server_time).isEqualTo("2026-10-10T08:15:00Z")
        assertThat(problem.request_id).isEqualTo("01J9Z6Q4V8M3N2P1R0S9T8U7V6")
    }
}
