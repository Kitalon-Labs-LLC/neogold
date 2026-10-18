package com.kitalonlabs.neogold.engine.camera.device

import android.app.ActivityManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies TC-DEV-01, TC-DEV-09, TC-DEV-10.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCheckerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun tcDev01_deviceCheckReportsCapabilities() {
        // TC-DEV-01: RAM, low-RAM flag, and camera hardware level recorded in device report
        val report = DeviceChecker.checkDevice(context)

        assertThat(report.capabilities.manufacturer).isNotEmpty()
        assertThat(report.capabilities.androidApi).isAtLeast(26)
        assertThat(report.capabilities.ramMb).isGreaterThan(0)
        assertThat(report.capabilities.cameraHardwareLevel).isNotNull()
        assertThat(report.batteryGuidance).isNotNull()
    }

    @Test
    fun tcDev09_batterySettingsGuidanceAllBrands() {
        // TC-DEV-09: Correct manufacturer steps and intent shortcuts for all 7 target brands
        val brands = listOf(
            "TECNO" to "HiOS",
            "Infinix" to "XOS",
            "itel" to "itel OS",
            "Xiaomi" to "MIUI / HyperOS",
            "Samsung" to "One UI",
            "Symphony" to "Symphony Android",
            "Walton" to "Walton Android"
        )

        for ((mfg, expectedSkin) in brands) {
            val guidance = DeviceBatteryGuidance.getGuidance(mfg, mfg)
            assertThat(guidance.skinName).isEqualTo(expectedSkin)
            assertThat(guidance.stepsEn).isNotEmpty()
            assertThat(guidance.stepsBn).isNotEmpty()
            assertThat(guidance.titleEn).isNotEmpty()
            assertThat(guidance.titleBn).isNotEmpty()

            // Fallback intent must point to app details
            val fallbackIntent = guidance.fallbackIntentFactory(context)
            assertThat(fallbackIntent.dataString).contains(context.packageName)

            // Primary intent is defined
            val primaryIntent = guidance.primaryIntentFactory(context)
            assertThat(primaryIntent).isNotNull()
        }
    }

    @Test
    fun tcDev10_supportedDeviceList() {
        // TC-DEV-10: Listed phone shows "tested"; unlisted phone shows "untested"
        val testedTecno = DeviceChecker.isTestedDevice("TECNO", "Spark 10 Pro")
        val testedInfinix = DeviceChecker.isTestedDevice("Infinix", "Hot 30 Play")
        val testedItel = DeviceChecker.isTestedDevice("itel", "A60s")
        val testedXiaomi = DeviceChecker.isTestedDevice("Xiaomi", "Redmi 13C")
        val testedSamsung = DeviceChecker.isTestedDevice("Samsung", "Galaxy A06")
        val testedWalton = DeviceChecker.isTestedDevice("Walton", "Primo S8")

        assertThat(testedTecno).isTrue()
        assertThat(testedInfinix).isTrue()
        assertThat(testedItel).isTrue()
        assertThat(testedXiaomi).isTrue()
        assertThat(testedSamsung).isTrue()
        assertThat(testedWalton).isTrue()

        // Unlisted phone
        val unlistedPixel = DeviceChecker.isTestedDevice("Google", "Pixel 9")
        assertThat(unlistedPixel).isFalse()
    }
}
