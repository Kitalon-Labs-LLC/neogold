package com.kitalonlabs.neogold.engine.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata

/**
 * Camera2 interop configuration for CameraX (doc 4 Section 5.1).
 * Turns off scene modes, effects, and video stabilization.
 * Configures the least aggressive noise reduction and edge enhancement available.
 * Converges and locks AE/AWB where supported.
 */
object CameraControlHelper {

    /**
     * Determines whether AE lock is available on this camera.
     */
    fun isAeLockSupported(characteristics: CameraCharacteristics): Boolean {
        return characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    }

    /**
     * Determines whether AWB lock is available on this camera.
     */
    fun isAwbLockSupported(characteristics: CameraCharacteristics): Boolean {
        return characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    }

    /**
     * Determines camera hardware level.
     */
    fun getHardwareLevel(characteristics: CameraCharacteristics): CameraHardwareLevel {
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        return CameraHardwareLevel.fromInt(level)
    }

    /**
     * Finds the least aggressive noise reduction mode supported by the sensor.
     * Prefers OFF (0) -> FAST (1) -> HIGH_QUALITY (2).
     */
    fun getLeastAggressiveNoiseReductionMode(characteristics: CameraCharacteristics): Int {
        val available = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: intArrayOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        return when {
            available.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
            available.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> available.firstOrNull() ?: CaptureRequest.NOISE_REDUCTION_MODE_FAST
        }
    }

    /**
     * Finds the least aggressive edge enhancement mode supported by the sensor.
     * Prefers OFF (0) -> FAST (1) -> HIGH_QUALITY (2).
     */
    fun getLeastAggressiveEdgeMode(characteristics: CameraCharacteristics): Int {
        val available = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: intArrayOf(CaptureRequest.EDGE_MODE_FAST)
        return when {
            available.contains(CaptureRequest.EDGE_MODE_OFF) -> CaptureRequest.EDGE_MODE_OFF
            available.contains(CaptureRequest.EDGE_MODE_FAST) -> CaptureRequest.EDGE_MODE_FAST
            else -> available.firstOrNull() ?: CaptureRequest.EDGE_MODE_FAST
        }
    }

    /**
     * Inspects camera characteristics and builds a summary of accepted/available controls.
     */
    fun inspectAvailableControls(characteristics: CameraCharacteristics): Map<String, Any?> {
        val nrMode = getLeastAggressiveNoiseReductionMode(characteristics)
        val edgeMode = getLeastAggressiveEdgeMode(characteristics)
        val aeLock = isAeLockSupported(characteristics)
        val awbLock = isAwbLockSupported(characteristics)
        val hwLevel = getHardwareLevel(characteristics)

        return mapOf(
            "hardwareLevel" to hwLevel.name,
            "supportsAeLock" to aeLock,
            "supportsAwbLock" to awbLock,
            "noiseReductionMode" to nrMode,
            "edgeEnhancementMode" to edgeMode,
            "controlModeAuto" to true,
            "sceneModeDisabled" to true,
            "effectModeOff" to true,
            "videoStabilizationOff" to true
        )
    }

    /**
     * Applies standard NeoGold capture controls to an ImageCapture.Builder via Camera2Interop.
     *
     * @param builder CameraX ImageCapture.Builder
     * @param characteristics Camera characteristics of the target lens
     * @param lockAe Whether to lock AE (if supported)
     * @param lockAwb Whether to lock AWB (if supported)
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun applyCaptureControls(
        builder: ImageCapture.Builder,
        characteristics: CameraCharacteristics,
        lockAe: Boolean = false,
        lockAwb: Boolean = false
    ) {
        val interop = Camera2Interop.Extender(builder)

        // Turn off scene modes and effects
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)

        // Video stabilization off
        interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        // Least aggressive noise reduction and edge modes
        val nrMode = getLeastAggressiveNoiseReductionMode(characteristics)
        interop.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)

        val edgeMode = getLeastAggressiveEdgeMode(characteristics)
        interop.setCaptureRequestOption(CaptureRequest.EDGE_MODE, edgeMode)

        // Flash off for skin screening
        interop.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        // Lock AE / AWB if requested and supported
        if (lockAe && isAeLockSupported(characteristics)) {
            interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (lockAwb && isAwbLockSupported(characteristics)) {
            interop.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    /**
     * Applies standard NeoGold controls to a Preview.Builder via Camera2Interop.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun applyPreviewControls(
        builder: Preview.Builder,
        characteristics: CameraCharacteristics
    ) {
        val interop = Camera2Interop.Extender(builder)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        interop.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        val nrMode = getLeastAggressiveNoiseReductionMode(characteristics)
        interop.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)

        val edgeMode = getLeastAggressiveEdgeMode(characteristics)
        interop.setCaptureRequestOption(CaptureRequest.EDGE_MODE, edgeMode)
    }

    /**
     * Extracts CaptureMetadata from an Android Camera2 CaptureResult.
     */
    fun extractMetadata(
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        acceptedControls: Map<String, Any?> = inspectAvailableControls(characteristics)
    ): CaptureMetadata {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aeLock = result.get(CaptureResult.CONTROL_AE_LOCK) ?: false
        val awbLock = result.get(CaptureResult.CONTROL_AWB_LOCK) ?: false
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)

        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val gainsArray = if (gains != null) {
            floatArrayOf(
                gains.red,
                gains.greenEven,
                gains.greenOdd,
                gains.blue
            )
        } else null

        val hwLevel = getHardwareLevel(characteristics)

        return CaptureMetadata(
            sensorSensitivity = iso,
            exposureTimeNs = expNs,
            aeLockSupported = isAeLockSupported(characteristics),
            awbLockSupported = isAwbLockSupported(characteristics),
            aeLocked = aeLock,
            awbLocked = awbLock,
            aeState = aeState,
            awbState = awbState,
            colorCorrectionGains = gainsArray,
            hardwareLevel = hwLevel,
            acceptedControls = acceptedControls
        )
    }

    /**
     * Checks if auto-exposure and auto-white-balance have converged.
     */
    fun isAeAwbConverged(result: CaptureResult): Boolean {
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)

        val aeOk = aeState == null ||
                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

        val awbOk = awbState == null ||
                awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED

        return aeOk && awbOk
    }
}
