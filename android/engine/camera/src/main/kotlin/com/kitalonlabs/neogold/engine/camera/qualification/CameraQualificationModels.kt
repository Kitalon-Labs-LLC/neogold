package com.kitalonlabs.neogold.engine.camera.qualification

import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import java.util.UUID

/**
 * Qualification outcome status.
 * Matches device_profile.qualification column in doc 5 Section 2.2.
 */
enum class QualificationStatus {
    QUALIFIED,
    RESEARCH_ONLY
}

/**
 * Profile active status in database.
 */
enum class DeviceProfileStatus {
    ACTIVE,
    SUPERSEDED,
    FAILED
}

/**
 * Reason a device is prompted to re-qualify (NG-044, TC-DEV-07).
 */
enum class RequalificationReason {
    EXPIRED_30_DAYS,
    OS_BUILD_CHANGED
}

/**
 * Audit record emitted upon qualification (TC-DEV-08, NFR-10).
 * Strictly numeric metrics and hardware metadata; zero personal identifiers.
 */
data class QualificationAuditEvent(
    val eventType: String, // "DEVICE_QUALIFIED" or "DEVICE_RESEARCH_ONLY"
    val timestampMs: Long,
    val deviceModel: String,
    val hardwareLevel: String,
    val repeatabilityDe: Double,
    val uniformityDe: Double,
    val calibrationResidualDe: Double,
    val qualification: QualificationStatus
)

/**
 * Configuration thresholds for device qualification.
 * Loaded from triage-config.json / qualityThresholds (doc 4 Section 5.1).
 */
data class QualificationThresholds(
    val maxRepeatabilityDeltaE: Double = 1.5,
    val maxUniformityDeltaE: Double = 2.0,
    val maxAccuracyDeltaE: Double = 3.0
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): QualificationThresholds {
            fun getDouble(key: String, default: Double): Double {
                val value = map[key] ?: return default
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: default
                    else -> default
                }
            }
            return QualificationThresholds(
                maxRepeatabilityDeltaE = getDouble("maxRepeatabilityDeltaE", 1.5),
                maxUniformityDeltaE = getDouble("maxPatchNonuniformityDelta", 2.0),
                maxAccuracyDeltaE = getDouble("maxCorrectionResidualDeltaE", 3.0)
            )
        }
    }
}

/**
 * Device profile record matching doc 5 Section 2.2 device_profile table.
 */
data class DeviceProfile(
    val id: String = UUID.randomUUID().toString(),
    val manufacturer: String,
    val deviceModel: String,
    val androidApi: Int,
    val buildFingerprint: String,
    val cameraId: String,
    val ramMb: Int,
    val isLowRamDevice: Boolean,
    val cameraHardwareLevel: CameraHardwareLevel,
    val supportsAeLock: Boolean,
    val supportsAwbLock: Boolean,
    val supportsManualSensor: Boolean,
    val supportsRaw: Boolean,
    val residualCorrectionJson: String?,
    val repeatabilityDe: Double,
    val uniformityDe: Double,
    val calibrationResidualDe: Double,
    val qualification: QualificationStatus,
    val cardBatchId: String,
    val calibratedAt: Long,
    val appVersion: String,
    val status: DeviceProfileStatus = DeviceProfileStatus.ACTIVE
)

/**
 * Detailed result of the qualification evaluation.
 */
data class QualificationResult(
    val status: QualificationStatus,
    val profile: DeviceProfile,
    val repeatabilityDe: Double,
    val uniformityDe: Double,
    val accuracyDe: Double,
    val repeatabilityPassed: Boolean,
    val uniformityPassed: Boolean,
    val accuracyPassed: Boolean,
    val auditEvent: QualificationAuditEvent,
    val failureReasons: List<String> = emptyList()
) {
    val isQualified: Boolean get() = status == QualificationStatus.QUALIFIED
}
