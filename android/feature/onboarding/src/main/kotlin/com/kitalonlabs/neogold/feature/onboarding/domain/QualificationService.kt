package com.kitalonlabs.neogold.feature.onboarding.domain

import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.dao.DeviceProfileDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.DeviceProfileEntity
import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.qualification.CameraQualifier
import com.kitalonlabs.neogold.engine.camera.qualification.DeviceProfile
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationAuditEvent
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationStatus
import com.kitalonlabs.neogold.engine.camera.qualification.RequalificationReason
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import kotlinx.coroutines.flow.firstOrNull
import java.util.Locale
import java.util.UUID

/** What S07 learns from one qualification photo before it counts. */
sealed interface PhotoCheck {
    data object Good : PhotoCheck

    /** The photo cannot be used; the first reason is the one to tell the user. Retake it. */
    data class Retake(val reasons: List<QualityReasonCode>) : PhotoCheck
}

/**
 * Qualifies this phone's camera on three photos of the scanned card and keeps the result (S07, NG-007, TC-DEV-01 to
 * TC-DEV-08).
 *
 * A photo the quality gate refuses (card not found, blurred, glare) is a bad photo, not a bad camera: [checkPhoto] sends
 * the user back to take it again, and only photos that pass reach [qualify]. The phone's own color accuracy is then
 * judged on measured values against the batch the user scanned, never on a built-in reference.
 */
class QualificationService(
    private val qualifier: CameraQualifier,
    private val vision: VisionEngine,
    private val cardBatches: CardBatchDao,
    private val profiles: DeviceProfileDao,
    private val audit: AuditEventDao,
    private val dataStore: NeoGoldDataStore,
    private val appVersion: String,
    private val clockMs: () -> Long = System::currentTimeMillis
) {

    /**
     * Runs the quality gate on one photo of the card against [reference]. The skin check on the card's window is left
     * out: these photos show the card on a table, with no baby, so the window shows the table.
     */
    fun checkPhoto(photo: ImageFrame, reference: Map<String, Lab>): PhotoCheck {
        val gate = vision.processFrame(photo, reference)
        val blocking = gate.failureReasons.filter { it != QualityReasonCode.WINDOW_NOT_SKIN }
        return if (blocking.isEmpty()) PhotoCheck.Good else PhotoCheck.Retake(blocking)
    }

    /**
     * The reference colors of a stored batch, or null if the batch is not stored, was not verified, or its values are
     * damaged. A batch kept without a verified signature is never a reference for qualification.
     */
    suspend fun referenceOf(batchId: String): Map<String, Lab>? =
        cardBatches.getBatch(batchId)?.takeIf { it.signatureValid == 1 }
            ?.let { CardBatchReference.fromJson(it.referenceValues) }

    /**
     * Qualifies the camera, then stores the profile (the earlier one is superseded), writes the audit event, and
     * remembers the OS build it was done on.
     *
     * @param photos the three photos that passed [checkPhoto]
     * @throws IllegalStateException when the batch is not stored: a profile names the batch it was qualified against
     */
    suspend fun qualify(
        photos: List<ImageFrame>,
        capabilities: DeviceCapabilities,
        cardBatchId: String
    ): QualificationResult {
        val reference = checkNotNull(referenceOf(cardBatchId)) { "The card batch is not stored" }
        val result = qualifier.qualifyCamera(
            photos = photos,
            batchReference = reference,
            capabilities = capabilities,
            cardBatchId = cardBatchId,
            appVersion = appVersion,
            currentTimeMs = clockMs()
        )
        // The new profile goes in first and the old ones are retired after, so there is no moment with no profile.
        val profile = result.profile.toEntity()
        profiles.insert(profile)
        profiles.supersedeOtherProfiles(profile.id)
        audit.insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                eventType = result.auditEvent.eventType,
                occurredAt = result.auditEvent.timestampMs,
                details = result.auditEvent.toDetails()
            )
        )
        dataStore.setActiveDeviceProfileId(result.profile.id)
        dataStore.setQualificationBuildFingerprint(capabilities.buildFingerprint)
        return result
    }

    /** Whether a profile is stored for this phone (onboarding that finished without one has to be done again). */
    suspend fun hasActiveProfile(): Boolean = profiles.getActiveProfile() != null

    /**
     * Whether screening may be offered on this phone: its camera was measured and qualified, and the measurement is not
     * due again. A phone that is research-only, has no profile, or is due, is not (H5, TC-DEV-05).
     */
    suspend fun screeningEligible(currentFingerprint: String): Boolean =
        profiles.getActiveProfile()?.qualification == QualificationStatus.QUALIFIED.name &&
            requalificationReason(currentFingerprint) == null

    /**
     * Whether the stored qualification has to be done again: 30 days old, or made on another OS build (NG-044,
     * TC-DEV-07). Null when there is no profile (nothing to renew) or it is still good.
     */
    suspend fun requalificationReason(currentFingerprint: String): RequalificationReason? {
        val profile = profiles.getActiveProfile() ?: return null
        // A clock set back to before the qualification would make the age negative and the rule never fire.
        val age = clockMs() - profile.calibratedAt
        if (age < 0 || age >= CameraQualifier.REQUALIFICATION_INTERVAL_MS) {
            return RequalificationReason.EXPIRED_30_DAYS
        }
        // No remembered build is not "the same build": a profile that does not say where it was made is made again.
        val qualifiedOn = dataStore.qualificationBuildFingerprint.firstOrNull()?.takeIf { it.isNotEmpty() }
        return if (qualifiedOn != currentFingerprint) RequalificationReason.OS_BUILD_CHANGED else null
    }

    private fun QualificationAuditEvent.toDetails(): String =
        "{\"deviceModel\":${jsonString(deviceModel)},\"hardwareLevel\":${jsonString(hardwareLevel)}," +
            "\"repeatabilityDe\":${jsonNumber(repeatabilityDe)},\"uniformityDe\":${jsonNumber(uniformityDe)}," +
            "\"calibrationResidualDe\":${jsonNumber(calibrationResidualDe)}," +
            "\"qualification\":${jsonString(qualification.name)}}"

    /** A JSON string with the characters JSON does not allow bare escaped. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < CONTROL_LIMIT -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** NaN and infinity are not JSON numbers; they are written as null. */
    private fun jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"

    private fun DeviceProfile.toEntity() = DeviceProfileEntity(
        id = id,
        manufacturer = manufacturer,
        deviceModel = deviceModel,
        androidApi = androidApi,
        cameraId = cameraId,
        ramMb = ramMb,
        isLowRamDevice = isLowRamDevice.toInt(),
        cameraHardwareLevel = cameraHardwareLevel.name,
        supportsAeLock = supportsAeLock.toInt(),
        supportsAwbLock = supportsAwbLock.toInt(),
        supportsManualSensor = supportsManualSensor.toInt(),
        supportsRaw = supportsRaw.toInt(),
        residualCorrection = residualCorrectionJson,
        repeatabilityDe = repeatabilityDe,
        uniformityDe = uniformityDe,
        calibrationResidualDe = calibrationResidualDe,
        qualification = qualification.name,
        cardBatchId = cardBatchId,
        calibratedAt = calibratedAt,
        appVersion = appVersion,
        status = status.name
    )

    private fun Boolean.toInt() = if (this) 1 else 0

    private companion object {
        /** Below this a character is a control character, which JSON requires to be escaped. */
        const val CONTROL_LIMIT = 0x20
    }
}
