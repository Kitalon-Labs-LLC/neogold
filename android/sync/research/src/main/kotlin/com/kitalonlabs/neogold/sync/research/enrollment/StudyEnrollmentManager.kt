package com.kitalonlabs.neogold.sync.research.enrollment

import android.os.Build
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.api.apiBaseUrl
import com.kitalonlabs.neogold.sync.research.api.models.DeviceInfo
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentRequest
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentResponse
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

class StudySettingsMismatchException(message: String) : Exception(message)

/**
 * Checks a scanned study QR against the trust list (doc 8 Section 4, K5). There is no "skip the check" value: a study
 * profile that is not verified is never used, because it names the server the phone will send research data to
 * (BUG-2026-040).
 */
internal fun interface StudyProfileVerifier {
    fun verify(qrPayload: ByteArray): VerificationResult<StudyProfilePayload>

    companion object {
        fun of(cardTrustVerifier: CardTrustVerifier) = StudyProfileVerifier { cardTrustVerifier.verifyStudyProfile(it) }
    }
}

/**
 * The API base URL a study profile names, checked and normalized: https, no user name, query or fragment, and no path
 * but the optional `/v1`. The host is lower-cased and a default port dropped, as the server compares them (doc 7 Section
 * 4.2). Anything else is refused, because this is the server the phone sends research data and its device assertions to.
 *
 * @param allowCleartext only the tests' local server; the app's network security config refuses cleartext anyway
 */
internal fun studyBaseUrl(host: String, allowCleartext: Boolean = false): String {
    val url = (if ("://" in host) host else "https://$host").toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Study profile host is not a valid address")
    require(url.isHttps || allowCleartext) { "Study profile host must use https" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Study profile host must be a plain address"
    }
    require(url.encodedPath == "/" || url.encodedPath == "/v1" || url.encodedPath == "/v1/") {
        "Study profile host must not name a path"
    }
    return apiBaseUrl(url.newBuilder().encodedPath("/").build().toString())
}

class StudyEnrollmentManager internal constructor(
    private val apiClient: ResearchApiClient,
    private val keyManager: DeviceKeyManager,
    private val studyProfileVerifier: StudyProfileVerifier,
    private val studyDao: ResearchStudyDao,
    private val appVersion: String,
    private val allowCleartext: Boolean
) {
    /** The only way the app builds one: the study profile is always checked against the trust list. */
    constructor(
        apiClient: ResearchApiClient,
        keyManager: DeviceKeyManager,
        cardTrustVerifier: CardTrustVerifier,
        studyDao: ResearchStudyDao,
        appVersion: String
    ) : this(apiClient, keyManager, StudyProfileVerifier.of(cardTrustVerifier), studyDao, appVersion, false)

    @Suppress("TooGenericExceptionCaught") // delete the device key on any enrollment failure, then rethrow
    suspend fun enroll(
        qrPayload: ByteArray,
        enrollmentCode: String,
        manufacturer: String = Build.MANUFACTURER ?: "Generic",
        model: String = Build.MODEL ?: "Generic",
        androidApi: Int = Build.VERSION.SDK_INT.coerceIn(Build.VERSION_CODES.O, 60),
        cameraHardwareLevel: String = "LIMITED",
        qualification: String = "QUALIFIED",
        idempotencyKey: String = UUID.randomUUID().toString()
    ): EnrollmentResponse {
        // 1. Verify study QR payload signature using CardTrustVerifier
        val profile = resolveStudyProfile(qrPayload)

        // 2. Validate one-time code format (Crockford base32: 3 groups of 4 chars)
        val cleanCode = enrollmentCode.trim().uppercase()
        val codeRegex = Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$")
        // The message never repeats the code: it is a credential, and messages reach logs and crash reports.
        require(codeRegex.matches(cleanCode)) { "Invalid enrollment code format" }

        val serverHost = profile.host ?: error("Study profile does not specify a server host")
        val baseUrl = studyBaseUrl(serverHost, allowCleartext)

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

    private fun resolveStudyProfile(qrPayload: ByteArray): StudyProfilePayload =
        when (val result = studyProfileVerifier.verify(qrPayload)) {
            is VerificationResult.Verified -> result.payload
            is VerificationResult.Expired -> throw IllegalArgumentException("Study profile has expired")
            else -> throw IllegalArgumentException("Study profile could not be verified")
        }
}
