package com.kitalonlabs.neogold.engine.camera.model

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.kitalonlabs.neogold.engine.vision.ImageFrame

/**
 * Camera hardware level classification matching Camera2 API.
 * Document 4 Section 5.1 step 5, Document 5 device_profile table.
 */
enum class CameraHardwareLevel {
    LEGACY,
    LIMITED,
    FULL,
    LEVEL_3,
    EXTERNAL,
    UNKNOWN;

    companion object {
        fun fromInt(level: Int?): CameraHardwareLevel {
            return when (level) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> LEGACY
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> LIMITED
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> FULL
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> LEVEL_3
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> EXTERNAL
                else -> UNKNOWN
            }
        }
    }
}

/**
 * CaptureResult metadata recorded with each image frame.
 * Document 4 Section 5.1, FR-11, NG-009, TC-CAP-15.
 */
data class CaptureMetadata(
    val sensorSensitivity: Int?, // ISO
    val exposureTimeNs: Long?,   // Exposure time in nanoseconds
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val aeLocked: Boolean,
    val awbLocked: Boolean,
    val aeState: Int?,           // CaptureResult.CONTROL_AE_STATE
    val awbState: Int?,          // CaptureResult.CONTROL_AWB_STATE
    val colorCorrectionGains: FloatArray?, // R, Gr, Gb, B or R, G, B gains
    val hardwareLevel: CameraHardwareLevel,
    val acceptedControls: Map<String, Any?> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CaptureMetadata

        if (sensorSensitivity != other.sensorSensitivity) return false
        if (exposureTimeNs != other.exposureTimeNs) return false
        if (aeLockSupported != other.aeLockSupported) return false
        if (awbLockSupported != other.awbLockSupported) return false
        if (aeLocked != other.aeLocked) return false
        if (awbLocked != other.awbLocked) return false
        if (aeState != other.aeState) return false
        if (awbState != other.awbState) return false
        if (colorCorrectionGains != null) {
            if (other.colorCorrectionGains == null) return false
            if (!colorCorrectionGains.contentEquals(other.colorCorrectionGains)) return false
        } else if (other.colorCorrectionGains != null) return false
        if (hardwareLevel != other.hardwareLevel) return false
        if (acceptedControls != other.acceptedControls) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sensorSensitivity ?: 0
        result = 31 * result + (exposureTimeNs?.hashCode() ?: 0)
        result = 31 * result + aeLockSupported.hashCode()
        result = 31 * result + awbLockSupported.hashCode()
        result = 31 * result + aeLocked.hashCode()
        result = 31 * result + awbLocked.hashCode()
        result = 31 * result + (aeState ?: 0)
        result = 31 * result + (awbState ?: 0)
        result = 31 * result + (colorCorrectionGains?.contentHashCode() ?: 0)
        result = 31 * result + hardwareLevel.hashCode()
        result = 31 * result + acceptedControls.hashCode()
        return result
    }
}

/**
 * Result of a burst capture of 2 or 3 frames.
 * Sharpest frame selected via Laplacian variance (doc 4 Section 5.1 step 5/F).
 */
data class BurstCaptureResult(
    val frames: List<ImageFrame>,
    val metadata: List<CaptureMetadata>,
    val sharpestIndex: Int
) {
    init {
        require(frames.isNotEmpty()) { "Burst capture must contain at least 1 frame" }
        require(frames.size == metadata.size) { "Frames and metadata lists must match in size" }
        require(sharpestIndex in frames.indices) { "sharpestIndex $sharpestIndex out of bounds" }
    }

    val selectedFrame: ImageFrame get() = frames[sharpestIndex]
    val selectedMetadata: CaptureMetadata get() = metadata[sharpestIndex]
}
