package com.kitalonlabs.neogold.engine.camera.qualification

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.model.CameraHardwareLevel
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import org.junit.Test

/**
 * Verifies TC-DEV-02, TC-DEV-03, TC-DEV-04, TC-DEV-05, TC-DEV-06, TC-DEV-07, TC-DEV-08.
 */
class CameraQualifierTest {

    private val renderer = SyntheticCardRenderer()
    private val qualifier = CameraQualifier()

    private fun createStandardCapabilities(
        hwLevel: CameraHardwareLevel = CameraHardwareLevel.FULL,
        aeLock: Boolean = true,
        awbLock: Boolean = true
    ) = DeviceCapabilities(
        manufacturer = "TECNO",
        deviceModel = "Spark 10",
        androidApi = 33,
        buildFingerprint = "TECNO/KI5k/KI5k:13/TP1A.220624.014/230918V1066:user/release-keys",
        ramMb = 4096,
        isLowRamDevice = false,
        cameraId = "0",
        cameraHardwareLevel = hwLevel,
        supportsAeLock = aeLock,
        supportsAwbLock = awbLock,
        supportsManualSensor = true,
        supportsRaw = false
    )

    @Test
    fun tcDev02_tcDev03_tcDev04_qualificationPass() {
        // Render 3 photos of the card at slight scale/angle variations
        val photo1 = renderer.renderFrame(SyntheticRenderConfig(scale = 1.0, tiltDegrees = 2.0))
        val photo2 = renderer.renderFrame(SyntheticRenderConfig(scale = 0.98, tiltDegrees = 2.5))
        val photo3 = renderer.renderFrame(SyntheticRenderConfig(scale = 1.02, tiltDegrees = 1.8))

        val result = qualifier.qualifyCamera(
            photos = listOf(photo1, photo2, photo3),
            capabilities = createStandardCapabilities(),
            cardBatchId = "NG-2026-001"
        )

        // TC-DEV-02: Repeatability < 1.5
        assertThat(result.repeatabilityDe).isLessThan(1.5)
        assertThat(result.repeatabilityPassed).isTrue()

        // TC-DEV-03: Uniformity < 2.0
        assertThat(result.uniformityDe).isLessThan(2.0)
        assertThat(result.uniformityPassed).isTrue()

        // TC-DEV-04: Accuracy < 3.0
        assertThat(result.accuracyDe).isLessThan(3.0)
        assertThat(result.accuracyPassed).isTrue()

        // Overall status QUALIFIED
        assertThat(result.status).isEqualTo(QualificationStatus.QUALIFIED)
        assertThat(result.isQualified).isTrue()
        assertThat(result.profile.qualification).isEqualTo(QualificationStatus.QUALIFIED)
        assertThat(result.profile.status).isEqualTo(DeviceProfileStatus.ACTIVE)
    }

    @Test
    fun tcDev05_localProcessingFailsPhone() {
        // TC-DEV-05: Force duplicate patch mismatch (e.g. HDR / local tone mapping)
        val photo1 = renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 3.8))
        val photo2 = renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.2))
        val photo3 = renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0))

        val result = qualifier.qualifyCamera(
            photos = listOf(photo1, photo2, photo3),
            capabilities = createStandardCapabilities(),
            cardBatchId = "NG-2026-001"
        )

        // Uniformity fails
        assertThat(result.uniformityPassed).isFalse()
        assertThat(result.uniformityDe).isGreaterThan(2.0)
        assertThat(result.status).isEqualTo(QualificationStatus.RESEARCH_ONLY)
        assertThat(result.isQualified).isFalse()
        assertThat(result.profile.qualification).isEqualTo(QualificationStatus.RESEARCH_ONLY)
    }

    @Test
    fun tcDev06_missingAeAwbLockDoesNotFailPhone() {
        // TC-DEV-06 (FR-11, ADR-012): Qualify a LEGACY camera without AE or AWB lock
        val photo1 = renderer.renderFrame(SyntheticRenderConfig(scale = 1.0))
        val photo2 = renderer.renderFrame(SyntheticRenderConfig(scale = 1.0))
        val photo3 = renderer.renderFrame(SyntheticRenderConfig(scale = 1.0))

        val legacyCapabilities = createStandardCapabilities(
            hwLevel = CameraHardwareLevel.LEGACY,
            aeLock = false,
            awbLock = false
        )

        val result = qualifier.qualifyCamera(
            photos = listOf(photo1, photo2, photo3),
            capabilities = legacyCapabilities,
            cardBatchId = "NG-2026-001"
        )

        assertThat(result.status).isEqualTo(QualificationStatus.QUALIFIED)
        assertThat(result.profile.cameraHardwareLevel).isEqualTo(CameraHardwareLevel.LEGACY)
        assertThat(result.profile.supportsAeLock).isFalse()
        assertThat(result.profile.supportsAwbLock).isFalse()
    }

    @Test
    fun tcDev07_requalificationPrompt() {
        // TC-DEV-07 (NG-044): Re-qualification after 30 days or OS fingerprint change
        val baseTime = 1700000000000L
        val originalFingerprint = "TECNO/KI5k/KI5k:13/TP1A.220624.014/230918V1066:user/release-keys"

        val profile = DeviceProfile(
            manufacturer = "TECNO",
            deviceModel = "Spark 10",
            androidApi = 33,
            buildFingerprint = originalFingerprint,
            cameraId = "0",
            ramMb = 4096,
            isLowRamDevice = false,
            cameraHardwareLevel = CameraHardwareLevel.FULL,
            supportsAeLock = true,
            supportsAwbLock = true,
            supportsManualSensor = true,
            supportsRaw = false,
            residualCorrectionJson = null,
            repeatabilityDe = 0.8,
            uniformityDe = 0.9,
            calibrationResidualDe = 1.2,
            qualification = QualificationStatus.QUALIFIED,
            cardBatchId = "NG-2026-001",
            calibratedAt = baseTime,
            appVersion = "1.0.0"
        )

        // 1. Within 30 days, same OS fingerprint -> No re-qualification
        val currentCheck = qualifier.checkRequalification(
            profile = profile,
            currentTimeMs = baseTime + (10L * 24L * 60L * 60L * 1000L),
            currentFingerprint = originalFingerprint
        )
        assertThat(currentCheck).isNull()

        // 2. Advance 30 days -> EXPIRED_30_DAYS
        val expiredCheck = qualifier.checkRequalification(
            profile = profile,
            currentTimeMs = baseTime + (31L * 24L * 60L * 60L * 1000L),
            currentFingerprint = originalFingerprint
        )
        assertThat(expiredCheck).isEqualTo(RequalificationReason.EXPIRED_30_DAYS)

        // 3. Within 30 days, but OS build fingerprint changed -> OS_BUILD_CHANGED
        val updatedOsCheck = qualifier.checkRequalification(
            profile = profile,
            currentTimeMs = baseTime + (5L * 24L * 60L * 60L * 1000L),
            currentFingerprint = "TECNO/KI5k/KI5k:14/UP1A.231005.007/240115V1201:user/release-keys"
        )
        assertThat(updatedOsCheck).isEqualTo(RequalificationReason.OS_BUILD_CHANGED)
    }

    @Test
    fun tcDev08_qualificationIsAudited() {
        // TC-DEV-08: Qualify, then fail one phone -> DEVICE_QUALIFIED and DEVICE_RESEARCH_ONLY events
        val passPhotos = listOf(
            renderer.renderFrame(SyntheticRenderConfig()),
            renderer.renderFrame(SyntheticRenderConfig()),
            renderer.renderFrame(SyntheticRenderConfig())
        )
        val passResult = qualifier.qualifyCamera(
            photos = passPhotos,
            capabilities = createStandardCapabilities(),
            cardBatchId = "NG-2026-001"
        )
        assertThat(passResult.auditEvent.eventType).isEqualTo("DEVICE_QUALIFIED")
        assertThat(passResult.auditEvent.qualification).isEqualTo(QualificationStatus.QUALIFIED)
        assertThat(passResult.auditEvent.repeatabilityDe).isAtLeast(0.0)

        // Fail case
        val failPhotos = listOf(
            renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0)),
            renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0)),
            renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 4.0))
        )
        val failResult = qualifier.qualifyCamera(
            photos = failPhotos,
            capabilities = createStandardCapabilities(),
            cardBatchId = "NG-2026-001"
        )
        assertThat(failResult.auditEvent.eventType).isEqualTo("DEVICE_RESEARCH_ONLY")
        assertThat(failResult.auditEvent.qualification).isEqualTo(QualificationStatus.RESEARCH_ONLY)
    }
}
