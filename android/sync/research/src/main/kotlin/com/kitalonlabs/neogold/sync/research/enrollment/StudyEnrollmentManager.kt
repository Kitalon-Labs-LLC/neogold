package com.kitalonlabs.neogold.sync.research.enrollment

import android.os.Build
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
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

/** Why a phone could not be set up on a study server before anything was sent. */
enum class EnrollmentRefusal {
    /** No study is set up on this phone yet: research mode is unlocked with the study QR first (R01). */
    NO_STUDY,

    /** The QR names a different study from the one on this phone. */
    OTHER_STUDY,

    /** This phone already has a device ID for the study; a second key would replace the one the server knows. */
    ALREADY_ENROLLED,

    /** The signed profile names no server: this study is exported by hand. */
    NO_SERVER
}

/** Enrollment refused before any key was made or request sent. The message never repeats a code or a host. */
class StudyEnrollmentRefused(val reason: EnrollmentRefusal) :
    Exception("Study enrollment refused: ${reason.name}")

/**
 * The largest research photo the app keeps (the same number as `ResearchCaptureProcessor.MAX_IMAGE_BYTES`; a test in `:app`
 * checks they agree). A server whose `max_image_bytes` policy is lower would refuse photos the app has already kept, so
 * enrollment is refused then (BUG-2026-083 item 8).
 */
const val APP_MAX_IMAGE_BYTES = 5_000_000L

/** The one-time code on a coordinator's sheet: Crockford base32 (no I, L, O, U), three groups of four. */
private val ENROLLMENT_CODE_FORMAT = Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$")

/** The Keystore alias of a study's device key is this prefix plus the study code. */
const val DEVICE_KEY_ALIAS_PREFIX = "neogold_device_key_"

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

/**
 * What the server is told about this phone when it enrolls (doc 7 `DeviceInfo`). The camera level and the qualification are
 * the facts the phone measured and stored in its active device profile; they have no default, because a made-up value
 * would put a wrong phone into the study's records.
 */
data class EnrollingDevice(
    val cameraHardwareLevel: String,
    val qualification: String,
    val manufacturer: String = Build.MANUFACTURER ?: "Generic",
    val model: String = Build.MODEL ?: "Generic",
    val androidApi: Int = Build.VERSION.SDK_INT.coerceIn(Build.VERSION_CODES.O, MAX_ANDROID_API)
)

private const val MAX_ANDROID_API = 60

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

    /**
     * Sets the phone up on the server of the study that is already on it. Everything that can be refused is refused
     * before a key is made or a request sent: a QR that is not trusted, a malformed code, no study on the phone, a QR of
     * another study, a phone that already has a device ID, a study with no server.
     *
     * @throws StudyEnrollmentRefused the phone cannot be set up on this study (see [EnrollmentRefusal])
     * @throws StudySettingsMismatchException the server's policy does not match the signed profile or the app's limits
     */
    @Suppress(
        "TooGenericExceptionCaught",
        "ThrowsCount"
    ) // each refusal is its own typed reason; the key is deleted on any failure
    suspend fun enroll(
        qrPayload: ByteArray,
        enrollmentCode: String,
        device: EnrollingDevice,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): EnrollmentResponse {
        // 1. Verify study QR payload signature using CardTrustVerifier
        val profile = resolveStudyProfile(qrPayload)

        // 2. Validate one-time code format (Crockford base32: 3 groups of 4 chars)
        val cleanCode = enrollmentCode.trim().uppercase()
        // The message never repeats the code: it is a credential, and messages reach logs and crash reports.
        require(ENROLLMENT_CODE_FORMAT.matches(cleanCode)) { "Invalid enrollment code format" }

        // 3. The study on this phone is the one that is joined: a phone has one study (BUG-2026-078), and a second enrollment
        // would replace the device key the server already knows.
        val study = studyDao.getActiveStudy() ?: throw StudyEnrollmentRefused(EnrollmentRefusal.NO_STUDY)
        if (study.studyCode != profile.studyCode) throw StudyEnrollmentRefused(EnrollmentRefusal.OTHER_STUDY)
        if (study.serverDeviceId != null) throw StudyEnrollmentRefused(EnrollmentRefusal.ALREADY_ENROLLED)
        val serverHost = profile.host ?: throw StudyEnrollmentRefused(EnrollmentRefusal.NO_SERVER)
        val baseUrl = studyBaseUrl(serverHost, allowCleartext)

        val keyAlias = "$DEVICE_KEY_ALIAS_PREFIX${profile.studyCode}"

        // 4. Generate non-exportable P-256 key with attestation challenge = SHA-256(cleanCode)
        // From here on, any failure leaves no key behind: the server either never knew it or the phone cannot use it.
        try {
            val keyResult = keyManager.generateDeviceKey(keyAlias, cleanCode)

            // 5. Call POST /enrollments
            val response = apiClient.enrollDevice(
                baseUrl = baseUrl,
                idempotencyKey = idempotencyKey,
                appVersion = appVersion,
                enrollmentRequest = EnrollmentRequest(
                    enrollment_code = cleanCode,
                    device_public_key = keyResult.publicJwk,
                    key_attestation_chain = keyResult.attestationChain,
                    device = DeviceInfo(
                        manufacturer = device.manufacturer,
                        model = device.model,
                        android_api = device.androidApi,
                        app_version = appVersion,
                        camera_hardware_level = device.cameraHardwareLevel,
                        qualification = device.qualification
                    )
                )
            )

            // 6. Settings check (TC-API-07): the server's policy has to be the one the signed profile promised, and its photo
            // limit has to hold the photos the app keeps (BUG-2026-083 item 8).
            if (response.upload_policy.timestamp_precision != profile.timestampPrecision.name ||
                response.upload_policy.max_image_bytes < APP_MAX_IMAGE_BYTES
            ) {
                throw StudySettingsMismatchException("Study settings do not match. Contact your coordinator.")
            }

            // 7. Save the device on the study row; the PIN and its counters stay as they are.
            // the row as it is now: the PIN counters may have moved while the server was answering
            val current = studyDao.getStudyById(study.id) ?: study
            studyDao.update(
                current.copy(
                    siteCode = response.site_code,
                    displayName = profile.displayName,
                    serverUrl = baseUrl,
                    deviceKeyAlias = keyAlias,
                    serverDeviceId = response.device_id,
                    timestampPrecision = response.upload_policy.timestamp_precision,
                    timeZone = response.upload_policy.time_zone,
                    dataGeneration = response.data_generation,
                    exportPublicKey = profile.exportPublicKey,
                    status = "ACTIVE",
                    enrolledAt = System.currentTimeMillis()
                )
            )
            return response
        } catch (e: Exception) {
            keyManager.deleteDeviceKey(keyAlias)
            throw e
        }
    }

    private fun resolveStudyProfile(qrPayload: ByteArray): StudyProfilePayload =
        when (val result = studyProfileVerifier.verify(qrPayload)) {
            is VerificationResult.Verified -> result.payload
            is VerificationResult.Expired -> throw IllegalArgumentException("Study profile has expired")
            else -> throw IllegalArgumentException("Study profile could not be verified")
        }
}
