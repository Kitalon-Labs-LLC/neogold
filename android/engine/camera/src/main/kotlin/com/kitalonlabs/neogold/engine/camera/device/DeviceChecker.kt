package com.kitalonlabs.neogold.engine.camera.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.kitalonlabs.neogold.engine.camera.CameraControlHelper
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel

/**
 * Support status on the public supported device list (NG-046, TC-DEV-10).
 */
enum class DeviceSupportStatus {
    TESTED,
    UNTESTED
}

/**
 * Hardware capabilities of the device and back camera.
 * Matches device_profile table columns in doc 5 Section 2.2.
 */
data class DeviceCapabilities(
    val manufacturer: String,
    val deviceModel: String,
    val androidApi: Int,
    val buildFingerprint: String,
    val ramMb: Int,
    val isLowRamDevice: Boolean,
    val cameraId: String,
    val cameraHardwareLevel: CameraHardwareLevel,
    val supportsAeLock: Boolean,
    val supportsAwbLock: Boolean,
    val supportsManualSensor: Boolean,
    val supportsRaw: Boolean
)

/**
 * Full device compatibility check report (S05, TC-DEV-01, TC-DEV-09, TC-DEV-10).
 */
data class DeviceCompatibilityReport(
    val capabilities: DeviceCapabilities,
    val supportStatus: DeviceSupportStatus,
    val batteryGuidance: BatteryGuidance
) {
    val isScreeningEligibleHardware: Boolean
        get() = capabilities.androidApi >= 26 // API 26 minimum (PRD D6); empirical qualification decides camera
}

object DeviceChecker {

    /**
     * Known device models tested in the matrix (doc 1 Section 13.5, doc 9 Section 4.2).
     */
    private val TESTED_DEVICE_PATTERNS = listOf(
        // Transsion entry
        Regex(".*(spark|camon|pova).*", RegexOption.IGNORE_CASE),
        Regex(".*(hot|smart|note).*", RegexOption.IGNORE_CASE),
        Regex(".*(itel|vision|a60|a70|p40|p55).*", RegexOption.IGNORE_CASE),
        // Xiaomi entry
        Regex(".*(redmi|poco|mi|24090rn|23129rn).*", RegexOption.IGNORE_CASE),
        // Samsung entry
        Regex(".*(sm-a06|sm-a05|sm-a04|galaxy a06|galaxy a05|galaxy a04).*", RegexOption.IGNORE_CASE),
        // Symphony / Walton
        Regex(".*(symphony|walton|primo|z35|z40|infinix).*", RegexOption.IGNORE_CASE)
    )

    fun isTestedDevice(manufacturer: String = Build.MANUFACTURER, model: String = Build.MODEL): Boolean {
        val brand = DeviceBrand.detect(manufacturer, model)
        if (brand == DeviceBrand.GENERIC) return false
        val combined = "$manufacturer $model"
        return TESTED_DEVICE_PATTERNS.any { it.matches(combined) }
    }

    /**
     * Checks device memory and capabilities.
     */
    fun checkDevice(context: Context): DeviceCompatibilityReport {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val reportedRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val ramMb = if (reportedRamMb > 0) {
            reportedRamMb
        } else {
            (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt().coerceAtLeast(1024)
        }
        val isLowRam = actManager?.isLowRamDevice ?: (ramMb < LOW_RAM_THRESHOLD_MB)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val (backCameraId, characteristics) = findBackCamera(cameraManager)

        val hwLevel = characteristics?.let { CameraControlHelper.getHardwareLevel(it) }
            ?: CameraHardwareLevel.UNKNOWN
        val supportsAeLock = characteristics?.let { CameraControlHelper.isAeLockSupported(it) } ?: false
        val supportsAwbLock = characteristics?.let { CameraControlHelper.isAwbLockSupported(it) } ?: false

        val capabilitiesList = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsManual = capabilitiesList.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )
        val supportsRaw = capabilitiesList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val capabilities = DeviceCapabilities(
            manufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidApi = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT,
            ramMb = ramMb,
            isLowRamDevice = isLowRam,
            cameraId = backCameraId ?: "0",
            cameraHardwareLevel = hwLevel,
            supportsAeLock = supportsAeLock,
            supportsAwbLock = supportsAwbLock,
            supportsManualSensor = supportsManual,
            supportsRaw = supportsRaw
        )

        val supportStatus = if (isTestedDevice(Build.MANUFACTURER, Build.MODEL)) {
            DeviceSupportStatus.TESTED
        } else {
            DeviceSupportStatus.UNTESTED
        }

        val batteryGuidance = DeviceBatteryGuidance.getGuidance(Build.MANUFACTURER, Build.BRAND)

        return DeviceCompatibilityReport(
            capabilities = capabilities,
            supportStatus = supportStatus,
            batteryGuidance = batteryGuidance
        )
    }

    private fun findBackCamera(cameraManager: CameraManager?): Pair<String?, CameraCharacteristics?> {
        if (cameraManager == null) return Pair(null, null)
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return Pair(id, chars)
                }
            }
            val firstId = cameraManager.cameraIdList.firstOrNull()
            return Pair(firstId, firstId?.let { cameraManager.getCameraCharacteristics(it) })
        } catch (_: Exception) {
            return Pair(null, null)
        }
    }
}

private const val LOW_RAM_THRESHOLD_MB = 2500
