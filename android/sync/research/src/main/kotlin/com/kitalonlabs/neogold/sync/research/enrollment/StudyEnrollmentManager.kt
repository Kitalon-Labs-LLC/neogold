package com.kitalonlabs.neogold.sync.research.enrollment

import android.os.Build
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.PayloadParser
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.api.models.DeviceInfo
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentRequest
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentResponse
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import java.util.UUID

class StudySettingsMismatchException(message: String) : Exception(message)

class StudyEnrollmentManager(
    private val apiClient: ResearchApiClient,
    private val keyManager: DeviceKeyManager,
    private val cardTrustVerifier: CardTrustVerifier?,
    private val studyDao: ResearchStudyDao,
    private val appVersion: String
) {
    suspend fun enroll(
        qrPayload: ByteArray,
        enrollmentCode: String,
        manufacturer: String = Build.MANUFACTURER ?: "Generic",
        model: String = Build.MODEL ?: "Generic",
        androidApi: Int = Build.VERSION.SDK_INT.coerceIn(26, 60),
        cameraHardwareLevel: String = "LIMITED",
        qualification: String = "QUALIFIED",
        idempotencyKey: String = UUID.randomUUID().toString()
    ): EnrollmentResponse {
        // 1. Verify study QR payload signature using CardTrustVerifier
        val profileResult = cardTrustVerifier?.verifyStudyProfile(qrPayload)
        val profile = if (profileResult is VerificationResult.Verified) {
            profileResult.payload
        } else if (profileResult is VerificationResult.Expired) {
            throw IllegalArgumentException("Study profile has expired")
        } else if (cardTrustVerifier == null) {
            PayloadParser.parseStudyProfile(qrPayload)
                ?: throw IllegalArgumentException("Malformed study profile QR")
        } else {
            throw IllegalArgumentException("Invalid study profile signature: $profileResult")
        }

        // 2. Validate one-time code format (Crockford base32: 3 groups of 4 chars)
        val cleanCode = enrollmentCode.trim().uppercase()
        val codeRegex = Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$")
        require(codeRegex.matches(cleanCode)) { "Invalid enrollment code format: $enrollmentCode" }

        val serverHost = profile.host ?: error("Study profile does not specify a server host")
        val baseUrl = if (serverHost.startsWith("http://") || serverHost.startsWith("https://")) {
            serverHost
        } else {
            "https://$serverHost"
        }

        val keyAlias = "neogold_device_key_${profile.studyCode}"

        // 3. Generate non-exportable P-256 key with attestation challenge = SHA-256(cleanCode)
        val keyResult = keyManager.generateDeviceKey(keyAlias, cleanCode)

        // 4. Call POST /enrollments
        val enrollmentRequest = EnrollmentRequest(
            enrollment_code = cleanCode,
            device_public_key = keyResult.publicJwk,
            key_attestation_chain = keyResult.attestationChain,
            device = DeviceInfo(
                manufacturer = manufacturer,
                model = model,
                android_api = androidApi,
                app_version = appVersion,
                camera_hardware_level = cameraHardwareLevel,
                qualification = qualification
            )
        )

        val response = try {
            apiClient.enrollDevice(
                baseUrl = baseUrl,
                idempotencyKey = idempotencyKey,
                appVersion = appVersion,
                enrollmentRequest = enrollmentRequest
            )
        } catch (e: Exception) {
            keyManager.deleteDeviceKey(keyAlias)
            throw e
        }

        // 5. Settings check: compare upload_policy.timestamp_precision with signed profile (TC-API-07)
        val profilePrecisionStr = profile.timestampPrecision.name
        if (response.upload_policy.timestamp_precision != profilePrecisionStr) {
            keyManager.deleteDeviceKey(keyAlias)
            throw StudySettingsMismatchException("Study settings do not match. Contact your coordinator.")
        }

        // 6. Save device and study to database
        val existingStudy = studyDao.getStudyByCode(profile.studyCode)
        val studyEntity = ResearchStudyEntity(
            id = existingStudy?.id ?: UUID.randomUUID().toString(),
            studyCode = profile.studyCode,
            siteCode = response.site_code,
            displayName = profile.displayName,
            serverUrl = baseUrl,
            deviceKeyAlias = keyAlias,
            serverDeviceId = response.device_id,
            pinHash = existingStudy?.pinHash ?: ByteArray(0),
            pinSalt = existingStudy?.pinSalt ?: ByteArray(0),
            failedPinAttempts = existingStudy?.failedPinAttempts ?: 0,
            lockedUntil = existingStudy?.lockedUntil,
            timestampPrecision = response.upload_policy.timestamp_precision,
            timeZone = response.upload_policy.time_zone,
            dataGeneration = response.data_generation,
            localRetentionAfterAckDays = 14,
            exportPublicKey = profile.exportPublicKey,
            status = "ACTIVE",
            enrolledAt = System.currentTimeMillis()
        )
        if (existingStudy != null) {
            studyDao.update(studyEntity)
        } else {
            studyDao.insert(studyEntity)
        }

        return response
    }
}
