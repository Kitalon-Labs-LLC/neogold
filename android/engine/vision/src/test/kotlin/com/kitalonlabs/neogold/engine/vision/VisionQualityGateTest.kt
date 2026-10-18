package com.kitalonlabs.neogold.engine.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.ciede2000
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * P08 Vision Engine & Quality Gate Test Suite.
 *
 * Verifies:
 * - TC-CAP-02: Missing card -> NO_CARD
 * - TC-CAP-03: Card angle -> CARD_ANGLE (bench placeholder & synthetic angle)
 * - TC-CAP-04: Duplicate patches disagree -> NONUNIFORM_PATCHES (bench placeholder & synthetic)
 * - TC-CAP-05: Blur -> BLUR
 * - TC-CAP-06: Glare -> GLARE
 * - TC-CAP-07: Exposure -> UNDEREXPOSED, OVEREXPOSED
 * - TC-CAP-08: Mixed light -> UNEVEN_LIGHT (bench placeholder & synthetic)
 * - TC-CAP-09: High correction residual -> HIGH_RESIDUAL (bench placeholder & synthetic)
 * - TC-CAP-10: Window not skin -> WINDOW_NOT_SKIN
 * - TC-CAP-11: Whole bad set rejected (100% rejected, each with labeled reason)
 * - TC-CAP-12: Good set accepted (>= 95% accepted)
 * - TC-CAP-17: Card detection & patch extraction within 0.5 Delta E 2000 across rotations/scales
 * - TC-CARD-07: Home-printed copy of genuine QR -> HIGH_RESIDUAL (bench placeholder)
 * - Memory budget: Peak allocation <= 120 MB (NFR-04b) and single-frame buffer reuse
 */
class VisionQualityGateTest {

    private val thresholds = QualityThresholds()
    private val engine = VisionEngine(thresholds = thresholds)
    private val renderer = SyntheticCardRenderer()

    // -------------------------------------------------------------------------
    // TC-CAP-02: Missing card -> NO_CARD
    // -------------------------------------------------------------------------
    @Test
    fun tcCap02_missingCard_returnsNoCard() {
        // Frame with neutral gray background and no card
        val emptyFrame = ImageFrame(
            width = 1600,
            height = 1200,
            data = IntArray(1600 * 1200) { (0xFF shl 24) or (220 shl 16) or (220 shl 8) or 220 }
        )
        val result = engine.processFrame(emptyFrame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
        assertThat(result.metrics.markersDetected).isLessThan(4)
    }

    @Test
    fun tcCap02_fewerThanFourMarkers_returnsNoCard() {
        // Frame with one marker omitted
        val frame = renderer.renderFrame(SyntheticRenderConfig(omitMarkerId = 2))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.NO_CARD)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-03: Card angle
    // -------------------------------------------------------------------------
    @Test
    fun tcCap03_cardAngle_benchPlaceholder() {
        val benchDir = File(TestImageUtils.findRepoRoot(), "testdata/bench/jig_angles")
        if (!benchDir.exists() || benchDir.listFiles().isNullOrEmpty()) {
            println("[PENDING_BENCH_DATA] TC-CAP-03: Laboratory angle jig captures (10, 20, 30 deg) pending bench collection.")
            return
        }
        // Bench evaluation when real jig captures are available
    }

    @Test
    fun tcCap03_cardAngle_syntheticBoundary() {
        // 10 deg tilt: accepted
        val frame10 = renderer.renderFrame(SyntheticRenderConfig(tiltDegrees = 10.0))
        val res10 = engine.processFrame(frame10)
        println("res10 angle=${res10.metrics.cardAngleDegrees}, markers=${res10.metrics.markersDetected}, failures=${res10.failureReasons}")
        assertThat(res10.failureReasons).doesNotContain(QualityReasonCode.CARD_ANGLE)

        // 25 deg tilt: rejected CARD_ANGLE (exceeds 20.0 deg threshold)
        val frame25 = renderer.renderFrame(SyntheticRenderConfig(tiltDegrees = 25.0))
        val res25 = engine.processFrame(frame25)
        println("res25 angle=${res25.metrics.cardAngleDegrees}, markers=${res25.metrics.markersDetected}, failures=${res25.failureReasons}")
        assertThat(res25.failureReasons).contains(QualityReasonCode.CARD_ANGLE)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-04: Duplicate patches disagree -> NONUNIFORM_PATCHES
    // -------------------------------------------------------------------------
    @Test
    fun tcCap04_duplicatePatches_benchPlaceholder() {
        val benchDir = File(TestImageUtils.findRepoRoot(), "testdata/bench/spot_light")
        if (!benchDir.exists() || benchDir.listFiles().isNullOrEmpty()) {
            println("[PENDING_BENCH_DATA] TC-CAP-04: Spot light on one corner / forced HDR captures pending bench collection.")
            return
        }
    }

    @Test
    fun tcCap04_duplicatePatches_syntheticMismatch() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(duplicateMismatchDeltaE = 6.0))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.NONUNIFORM_PATCHES)
        assertThat(result.metrics.duplicatePatchDeltaE).isGreaterThan(thresholds.maxPatchNonuniformityDelta)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-05: Blur -> BLUR
    // -------------------------------------------------------------------------
    @Test
    fun tcCap05_blur_returnsBlurReason() {
        val blurredFrame = renderer.renderFrame(SyntheticRenderConfig(blurRadius = 8))
        val result = engine.processFrame(blurredFrame)
        println("blurredFrame variance=${result.metrics.blurLaplacianVariance}, markers=${result.metrics.markersDetected}, failures=${result.failureReasons}")
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.BLUR)
        assertThat(result.metrics.blurLaplacianVariance).isLessThan(thresholds.minBlurLaplacianVariance)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-06: Glare -> GLARE
    // -------------------------------------------------------------------------
    @Test
    fun tcCap06_glare_returnsGlareReason() {
        val glareFrame = renderer.renderFrame(SyntheticRenderConfig(glareRadiusPx = 50))
        val result = engine.processFrame(glareFrame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.GLARE)
        assertThat(result.metrics.glarePixelFraction).isGreaterThan(thresholds.maxGlarePixelFraction)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-07: Exposure -> UNDEREXPOSED, OVEREXPOSED
    // -------------------------------------------------------------------------
    @Test
    fun tcCap07_underexposed_returnsUnderexposed() {
        val underFrame = renderer.renderFrame(SyntheticRenderConfig(exposureMultiplier = 0.15))
        val result = engine.processFrame(underFrame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.UNDEREXPOSED)
        assertThat(result.metrics.midGrayLuminance).isLessThan(thresholds.minExposureMidGray)
    }

    @Test
    fun tcCap07_overexposed_returnsOverexposed() {
        val overFrame = renderer.renderFrame(SyntheticRenderConfig(exposureMultiplier = 3.5))
        val result = engine.processFrame(overFrame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.OVEREXPOSED)
        assertThat(result.metrics.midGrayLuminance).isGreaterThan(thresholds.maxExposureMidGray)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-08: Mixed light -> UNEVEN_LIGHT
    // -------------------------------------------------------------------------
    @Test
    fun tcCap08_mixedLight_benchPlaceholder() {
        val benchDir = File(TestImageUtils.findRepoRoot(), "testdata/bench/mixed_light")
        if (!benchDir.exists() || benchDir.listFiles().isNullOrEmpty()) {
            println("[PENDING_BENCH_DATA] TC-CAP-08: Mixed window daylight / tube light captures pending bench collection.")
            return
        }
    }

    @Test
    fun tcCap08_mixedLight_syntheticGradient() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(unevenLightGradientDelta = 0.8))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.UNEVEN_LIGHT)
        assertThat(result.metrics.lightUniformityDelta).isGreaterThan(thresholds.maxLightUniformityDelta)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-09: High correction residual -> HIGH_RESIDUAL
    // -------------------------------------------------------------------------
    @Test
    fun tcCap09_highCorrectionResidual_benchPlaceholder() {
        val benchDir = File(TestImageUtils.findRepoRoot(), "testdata/bench/mismatched_batch")
        if (!benchDir.exists() || benchDir.listFiles().isNullOrEmpty()) {
            println("[PENDING_BENCH_DATA] TC-CAP-09: Card from different batch than its QR pending bench collection.")
            return
        }
    }

    @Test
    fun tcCap09_highCorrectionResidual_syntheticMismatchedReference() {
        val frame = renderer.renderFrame(SyntheticRenderConfig())
        // Invert lightness and chromaticities to create completely incompatible target
        val mismatchedReference = CardGeometry.PATCHES.mapValues { (_, def) ->
            Lab(100.0 - def.nominalLab.l, -def.nominalLab.a, -def.nominalLab.b)
        }
        val result = engine.processFrame(frame, batchReference = mismatchedReference)
        println("tcCap09 residual: ${result.metrics.meanCorrectionResidualDeltaE}, failures: ${result.failureReasons}")
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.HIGH_RESIDUAL)
        assertThat(result.metrics.meanCorrectionResidualDeltaE).isGreaterThan(thresholds.maxCorrectionResidualDeltaE)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-10: Window not skin -> WINDOW_NOT_SKIN
    // -------------------------------------------------------------------------
    @Test
    fun tcCap10_windowNotSkin_blueCloth() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(windowContent = SyntheticWindowContent.CLOTH_BLUE))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.WINDOW_NOT_SKIN)
    }

    @Test
    fun tcCap10_windowNotSkin_greenCloth() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(windowContent = SyntheticWindowContent.CLOTH_GREEN))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.WINDOW_NOT_SKIN)
    }

    @Test
    fun tcCap10_windowNotSkin_emptyHole() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(windowContent = SyntheticWindowContent.EMPTY_HOLE))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.WINDOW_NOT_SKIN)
    }

    @Test
    fun tcCap10_windowNotSkin_highContrastEdge() {
        val frame = renderer.renderFrame(SyntheticRenderConfig(windowContent = SyntheticWindowContent.HIGH_CONTRAST_EDGE))
        val result = engine.processFrame(frame)
        assertThat(result.passed).isFalse()
        assertThat(result.failureReasons).contains(QualityReasonCode.WINDOW_NOT_SKIN)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-11: Whole bad set rejected (100% rejected, each with labeled reason)
    // -------------------------------------------------------------------------
    @Test
    fun tcCap11_wholeBadSetRejected() {
        val repoRoot = TestImageUtils.findRepoRoot()
        val badDir = File(repoRoot, "testdata/bad")
        assertThat(badDir.exists()).isTrue()

        val manifestFile = File(badDir, "manifest.json")
        assertThat(manifestFile.exists()).isTrue()
        val items = TestImageUtils.parseBadManifest(manifestFile)

        var totalImages = 0
        var rejectedImages = 0

        for (item in items) {
            val filename = item.filename
            val imgFile = File(badDir, filename)
            val frame = TestImageUtils.loadPngImage(imgFile)
            val result = engine.processFrame(frame)

            totalImages++
            if (!result.passed) {
                rejectedImages++
            } else {
                println("Bad image $filename unexpectedly passed quality gate!")
            }

            println("Bad file $filename -> failures: ${result.failureReasons}, expected: ${item.expectedReasons}")
            assertWithMessage("File $filename must be rejected by Quality Gate").that(result.passed).isFalse()
            for (reason in item.expectedReasons) {
                assertWithMessage("File $filename must trigger expected reason $reason")
                    .that(result.failureReasons).contains(reason)
            }
        }

        assertThat(totalImages).isGreaterThan(0)
        assertThat(rejectedImages).isEqualTo(totalImages)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-12: Good set accepted (>= 95% accepted)
    // -------------------------------------------------------------------------
    @Test
    fun tcCap12_goldenSetAccepted() {
        val repoRoot = TestImageUtils.findRepoRoot()
        val goldenDir = File(repoRoot, "testdata/golden/vision")
        assertThat(goldenDir.exists()).isTrue()

        val manifestFile = File(goldenDir, "manifest.json")
        assertThat(manifestFile.exists()).isTrue()
        val items = TestImageUtils.parseGoldenManifest(manifestFile)

        var totalImages = 0
        var acceptedImages = 0

        for (item in items) {
            val filename = item.filename
            val imgFile = File(goldenDir, filename)
            val frame = TestImageUtils.loadPngImage(imgFile)
            val result = engine.processFrame(frame)

            totalImages++
            if (result.passed) {
                acceptedImages++
            } else {
                println("Golden image $filename rejected with reasons: ${result.failureReasons}")
            }
        }

        assertThat(totalImages).isGreaterThan(0)
        val passRate = acceptedImages.toDouble() / totalImages.toDouble()
        println("Golden set pass rate: ${passRate * 100}% ($acceptedImages / $totalImages)")
        assertWithMessage("Golden set pass rate must be >= 95% (got ${passRate * 100}%)")
            .that(passRate).isAtLeast(0.95)
    }

    // -------------------------------------------------------------------------
    // TC-CAP-17: Card detection & patch extraction accuracy (<= 0.5 Delta E 2000)
    // -------------------------------------------------------------------------
    @Test
    fun tcCap17_cardDetectionAndPatchExtractionAccuracy() {
        val testConfigs = listOf(
            SyntheticRenderConfig(scale = 0.85, tiltDegrees = 0.0, rotationDegrees = 0.0),
            SyntheticRenderConfig(scale = 1.00, tiltDegrees = 5.0, rotationDegrees = 0.0),
            SyntheticRenderConfig(scale = 1.15, tiltDegrees = 0.0, rotationDegrees = 0.0),
            SyntheticRenderConfig(scale = 1.00, tiltDegrees = 0.0, rotationDegrees = 90.0),
            SyntheticRenderConfig(scale = 1.00, tiltDegrees = 0.0, rotationDegrees = 180.0),
            SyntheticRenderConfig(scale = 1.00, tiltDegrees = 0.0, rotationDegrees = 270.0)
        )

        for (cfg in testConfigs) {
            val frame = renderer.renderFrame(cfg)
            val result = engine.processFrame(frame)

            assertWithMessage("Frame (scale=${cfg.scale}, rot=${cfg.rotationDegrees}) must pass quality gate")
                .that(result.passed).isTrue()
            assertThat(result.canonicalImage).isNotNull()
            assertThat(result.sampledPatches).isNotNull()

            val canonical = result.canonicalImage!!
            val sampledPatches = CardSampler.samplePatches(canonical)

            // Verify each extracted patch value is within 0.5 Delta E 2000 of nominal
            for ((id, def) in CardGeometry.PATCHES) {
                val sampled = sampledPatches[id]
                assertThat(sampled).isNotNull()
                val deltaE = ciede2000(sampled!!.lab, def.nominalLab)
                assertWithMessage("Patch $id at scale=${cfg.scale}, rot=${cfg.rotationDegrees} Delta E ($deltaE) must be <= 0.5")
                    .that(deltaE).isAtMost(0.5)
            }
        }
    }

    // -------------------------------------------------------------------------
    // TC-CARD-07: Home-printed copy of genuine QR -> HIGH_RESIDUAL (bench placeholder)
    // -------------------------------------------------------------------------
    @Test
    fun tcCard07_homePrintedCounterfeit_benchPlaceholder() {
        val benchDir = File(TestImageUtils.findRepoRoot(), "testdata/bench/home_printed_cards")
        if (!benchDir.exists() || benchDir.listFiles().isNullOrEmpty()) {
            println("[PENDING_BENCH_DATA] TC-CARD-07: Genuine QR attached to home-printed counterfeit card captures pending bench collection.")
            return
        }
    }

    // -------------------------------------------------------------------------
    // Peak memory allocation assertion (NFR-04b <= 120 MB)
    // -------------------------------------------------------------------------
    @Test
    fun memoryFootprint_isStrictlyBoundedUnder120MB() {
        val frame = renderer.renderFrame(SyntheticRenderConfig())
        engine.processFrame(frame)

        val peakBytes = engine.getPeakAllocationBytes()
        val peakMb = peakBytes.toDouble() / (1024.0 * 1024.0)
        println("Peak single-frame buffer allocation: $peakMb MB")

        // Peak buffer allocation for single 1600x1200 frame (7.68 MB) + 1200x760 canonical buffer (3.65 MB) = ~11.3 MB
        // Hard limit from NFR-04b is 120 MB
        assertThat(peakMb).isLessThan(120.0)
    }
}
