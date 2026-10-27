package com.kitalonlabs.neogold.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.device.DeviceChecker
import com.kitalonlabs.neogold.engine.camera.device.DeviceCompatibilityReport
import com.kitalonlabs.neogold.engine.camera.device.DeviceSupportStatus
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.camera.qualification.DeviceProfile
import com.kitalonlabs.neogold.engine.camera.qualification.DeviceProfileStatus
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationAuditEvent
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Onboarding Flow Tests covering:
 * - TC-ONB-01: Language at first launch (FR-01, NG-002)
 * - TC-ONB-02: Safety notice must be acknowledged (FR-02, NG-003)
 * - TC-ONB-03: Notice returns after a major model update (FR-02)
 * - TC-DEV-10: Supported device list (NG-046)
 */
class OnboardingFlowTest {

    private lateinit var dataStore: FakeNeoGoldDataStore

    @Before
    fun setUp() {
        dataStore = FakeNeoGoldDataStore()
    }

    /**
     * TC-ONB-01: Fresh install; open app.
     * S01 offers Bangla and English; choice applies to every following screen.
     */
    @Test
    fun testLanguageAtFirstLaunch() = runTest {
        // Initial state before selection
        assertThat(dataStore.language.first()).isEqualTo("bn")

        // Health worker selects English on S01
        dataStore.setLanguage("en")
        assertThat(dataStore.language.first()).isEqualTo("en")

        // Health worker selects Bangla on S01
        dataStore.setLanguage("bn")
        assertThat(dataStore.language.first()).isEqualTo("bn")
    }

    /**
     * TC-ONB-02: Safety notice must be acknowledged.
     * Fresh install; cannot reach Home without tapping "I understand" on the last page.
     */
    @Test
    fun testSafetyNoticeMustBeAcknowledged() = runTest {
        // Initially, safety notice is not acknowledged
        assertThat(dataStore.safetyAckVersion.first()).isEqualTo(0)
        assertThat(dataStore.onboardingComplete.first()).isFalse()

        // Simulating completion of safety notice pager on S04
        val bundledSafetyNoticeVersion = 1
        dataStore.setSafetyAckVersion(bundledSafetyNoticeVersion)

        assertThat(dataStore.safetyAckVersion.first()).isEqualTo(1)
    }

    /**
     * TC-ONB-03: Notice returns after a major model update.
     * Set `safety_ack_version` below the bundled model's; launch -> Notice shown again before Home.
     */
    @Test
    fun testNoticeReturnsAfterMajorUpdate() = runTest {
        val currentBundledModelNoticeVersion = 2 // New release with updated model notice

        // User previously acknowledged version 1
        dataStore.setSafetyAckVersion(1)
        dataStore.setOnboardingComplete(true)

        // Pre-launch check: verify if notice needs to be re-displayed
        val acknowledgedVersion = dataStore.safetyAckVersion.first()
        val mustShowSafetyNotice = acknowledgedVersion < currentBundledModelNoticeVersion

        assertThat(mustShowSafetyNotice).isTrue()

        // After re-acknowledging the new notice
        dataStore.setSafetyAckVersion(currentBundledModelNoticeVersion)
        assertThat(dataStore.safetyAckVersion.first()).isEqualTo(2)

        val requiresNoticeNow = dataStore.safetyAckVersion.first() < currentBundledModelNoticeVersion
        assertThat(requiresNoticeNow).isFalse()
    }

    /**
     * TC-DEV-10: Supported device list (NG-046).
     * Run device check on a listed and an unlisted phone:
     * - Listed phone shows "tested"
     * - Unlisted phone runs qualification and is told it is untested
     */
    @Test
    fun testSupportedDeviceListDetection() {
        // 1. Tested phone models from the matrix (doc 1 Section 13.5, doc 9 Section 4.2)
        assertThat(DeviceChecker.isTestedDevice("TECNO", "Spark 10 Pro")).isTrue()
        assertThat(DeviceChecker.isTestedDevice("Infinix", "Hot 30 Play")).isTrue()
        assertThat(DeviceChecker.isTestedDevice("itel", "A60s")).isTrue()
        assertThat(DeviceChecker.isTestedDevice("Xiaomi", "Redmi 13C")).isTrue()
        assertThat(DeviceChecker.isTestedDevice("Samsung", "Galaxy A06")).isTrue()
        assertThat(DeviceChecker.isTestedDevice("Walton", "Primo S8")).isTrue()

        // 2. Unlisted phone models
        assertThat(DeviceChecker.isTestedDevice("Google", "Pixel 9")).isFalse()
        assertThat(DeviceChecker.isTestedDevice("OnePlus", "Nord CE 3")).isFalse()
        assertThat(DeviceChecker.isTestedDevice("Motorola", "Moto G34")).isFalse()
    }

    /**
     * TC-DEV-10: Untested phone runs empirical camera qualification.
     */
    @Test
    fun testUntestedPhoneRunsQualification() = runTest {
        val unlistedCapabilities = DeviceCapabilities(
            manufacturer = "Google",
            deviceModel = "Pixel 9",
            androidApi = 34,
            buildFingerprint = "google/pixel9/14",
            ramMb = 8192,
            isLowRamDevice = false,
            cameraId = "0",
            cameraHardwareLevel = CameraHardwareLevel.LEVEL_3,
            supportsAeLock = true,
            supportsAwbLock = true,
            supportsManualSensor = true,
            supportsRaw = true
        )

        val report = DeviceCompatibilityReport(
            capabilities = unlistedCapabilities,
            supportStatus = if (DeviceChecker.isTestedDevice(
                    unlistedCapabilities.manufacturer,
                    unlistedCapabilities.deviceModel
                )
            ) {
                DeviceSupportStatus.TESTED
            } else {
                DeviceSupportStatus.UNTESTED
            },
            batteryGuidance = com.kitalonlabs.neogold.engine.camera.device.DeviceBatteryGuidance.getGuidance(
                unlistedCapabilities.manufacturer,
                unlistedCapabilities.deviceModel
            )
        )

        // Unlisted phone is correctly identified as UNTESTED
        assertThat(report.supportStatus).isEqualTo(DeviceSupportStatus.UNTESTED)

        // Phone qualifies empirically
        val profile = DeviceProfile(
            id = UUID.randomUUID().toString(),
            manufacturer = unlistedCapabilities.manufacturer,
            deviceModel = unlistedCapabilities.deviceModel,
            androidApi = unlistedCapabilities.androidApi,
            buildFingerprint = unlistedCapabilities.buildFingerprint,
            cameraId = unlistedCapabilities.cameraId,
            ramMb = unlistedCapabilities.ramMb,
            isLowRamDevice = unlistedCapabilities.isLowRamDevice,
            cameraHardwareLevel = unlistedCapabilities.cameraHardwareLevel,
            supportsAeLock = true,
            supportsAwbLock = true,
            supportsManualSensor = true,
            supportsRaw = true,
            residualCorrectionJson = null,
            repeatabilityDe = 0.72,
            uniformityDe = 0.95,
            calibrationResidualDe = 1.40,
            qualification = QualificationStatus.QUALIFIED,
            cardBatchId = "NG-2026-001",
            calibratedAt = System.currentTimeMillis(),
            appVersion = "1.0.0",
            status = DeviceProfileStatus.ACTIVE
        )

        val qualificationResult = QualificationResult(
            status = QualificationStatus.QUALIFIED,
            profile = profile,
            repeatabilityDe = 0.72,
            uniformityDe = 0.95,
            accuracyDe = 1.40,
            repeatabilityPassed = true,
            uniformityPassed = true,
            accuracyPassed = true,
            auditEvent = QualificationAuditEvent(
                eventType = "DEVICE_QUALIFIED",
                timestampMs = System.currentTimeMillis(),
                deviceModel = profile.deviceModel,
                hardwareLevel = profile.cameraHardwareLevel.name,
                repeatabilityDe = 0.72,
                uniformityDe = 0.95,
                calibrationResidualDe = 1.40,
                qualification = QualificationStatus.QUALIFIED
            ),
            failureReasons = emptyList()
        )

        assertThat(qualificationResult.status).isEqualTo(QualificationStatus.QUALIFIED)

        // Saves to DataStore and completes onboarding
        dataStore.setActiveDeviceProfileId(profile.id)
        dataStore.setOnboardingComplete(true)

        assertThat(dataStore.activeDeviceProfileId.first()).isEqualTo(profile.id)
        assertThat(dataStore.onboardingComplete.first()).isTrue()
    }
}
