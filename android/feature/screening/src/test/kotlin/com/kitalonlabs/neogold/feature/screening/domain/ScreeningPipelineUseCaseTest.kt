package com.kitalonlabs.neogold.feature.screening.domain

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import com.kitalonlabs.neogold.engine.vision.SyntheticWindowContent
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Tests for [ScreeningPipelineUseCase].
 * Verifies end-to-end execution from image frame to estimation and triage,
 * intermediate data population, version traceability (NFR-10), and quality failure handling.
 */
class ScreeningPipelineUseCaseTest {

    private lateinit var pipelineUseCase: ScreeningPipelineUseCase
    private lateinit var estimationEngine: EstimationEngine
    private lateinit var triageEngine: TriageEngine
    private lateinit var testCardBatch: CardBatchPayload
    private lateinit var testVersions: VersionsRecord

    private val repoRoot: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null) {
                if (File(dir, "testdata").isDirectory && (File(dir, ".git").isDirectory || File(dir, "docs").isDirectory)) {
                    return dir
                }
                dir = dir.parentFile!!
            }
            return File("../../..").canonicalFile
        }

    @Before
    fun setUp() {
        pipelineUseCase = ScreeningPipelineUseCase(visionEngine = VisionEngine())

        val modelFile = File(repoRoot, "testdata/models/model.synthetic.json")
        estimationEngine = EstimationEngine.fromJson(modelFile.readText())

        val configFile = File(repoRoot, "testdata/triage/config.synthetic.json")
        val config = TriageConfigParser.parse(configFile.readText())
        triageEngine = TriageEngine(config)

        testVersions = VersionsRecord(
            appVersion = "1.0.0",
            modelVersion = estimationEngine.model.modelVersion,
            configVersion = config.configVersion,
            cardBatchId = "BATCH-2026-TEST",
            deviceModel = "Pixel 8",
            deviceManufacturer = "Google",
            osVersion = "Android 15"
        )

        // Nominal Card v1 patch references
        val patchRefs = CardGeometry.PATCHES.values.mapIndexed { idx, p ->
            PatchReference(
                index = idx,
                lStar = p.nominalLab.l,
                aStar = p.nominalLab.a,
                bStar = p.nominalLab.b
            )
        }

        testCardBatch = CardBatchPayload(
            formatVersion = 1,
            issuedAtDays = 1000,
            expiresAtDays = 3000,
            issuedAt = LocalDate.now().minusDays(10),
            expiresAt = LocalDate.now().plusDays(365),
            issuerKeyId = "neogold-issuer-root",
            batchId = "BATCH-2026-TEST",
            patchCount = 23,
            patches = patchRefs,
            signedBytes = ByteArray(100),
            signature = ByteArray(64)
        )
    }

    @Test
    fun testExecute_successEndToEnd_populatesAllIntermediateData() {
        val renderer = SyntheticCardRenderer()
        val frame = renderer.renderFrame(
            SyntheticRenderConfig(
                windowContent = SyntheticWindowContent.SKIN_JAUNDICE
            )
        )

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )

        val result = pipelineUseCase.execute(
            frame = frame,
            cardBatch = testCardBatch,
            inputs = inputs,
            attempt = 1,
            estimationEngine = estimationEngine,
            triageEngine = triageEngine,
            versions = testVersions
        )

        assertThat(result).isInstanceOf(PipelineExecutionResult.Success::class.java)
        val success = result as PipelineExecutionResult.Success
        val intermediate = success.intermediateData

        // Verify quality gate passed
        assertThat(intermediate.qualityResult.passed).isTrue()
        assertThat(intermediate.qualityResult.failureReasons).isEmpty()

        // Verify CCM matrix is 3x3
        assertThat(intermediate.ccmMatrix).hasSize(3)
        assertThat(intermediate.ccmMatrix[0]).hasSize(3)

        // Verify features extracted
        assertThat(intermediate.extractedFeatures.keys).containsAtLeast("l_star", "a_star", "b_star", "b_over_l", "a_over_b")

        // Verify estimation output
        assertThat(intermediate.estimationOutput.estimate).isGreaterThan(0.0)
        assertThat(intermediate.estimationOutput.lowerBound).isLessThan(intermediate.estimationOutput.upperBound)

        // Verify triage result
        assertThat(intermediate.triageResult).isInstanceOf(TriageResult.Action::class.java)
        val action = intermediate.triageResult as TriageResult.Action
        assertThat(action.configVersion).isEqualTo(testVersions.configVersion)

        // Verify traceability versions (NFR-10, NG-025)
        assertThat(intermediate.versions.cardBatchId).isEqualTo("BATCH-2026-TEST")
        assertThat(intermediate.versions.appVersion).isEqualTo("1.0.0")

        // Verify stage timings recorded (doc 4 Section 5.6)
        assertThat(intermediate.stageTimingsMs.keys).containsAtLeast(
            "decode_warp_sample_ms",
            "quality_and_analysis_ms",
            "total_capture_to_result_ms"
        )
    }

    @Test
    fun testExecute_missingCard_returnsQualityFailed() {
        // Blank black frame (no markers, no card)
        val blankFrame = ImageFrame(1600, 1200, IntArray(1600 * 1200) { 0xFF000000.toInt() })

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )

        val result = pipelineUseCase.execute(
            frame = blankFrame,
            cardBatch = testCardBatch,
            inputs = inputs,
            attempt = 1,
            estimationEngine = estimationEngine,
            triageEngine = triageEngine,
            versions = testVersions
        )

        assertThat(result).isInstanceOf(PipelineExecutionResult.QualityFailed::class.java)
        val failed = result as PipelineExecutionResult.QualityFailed
        assertThat(failed.qualityResult.passed).isFalse()
        assertThat(failed.qualityResult.failureReasons).contains(QualityReasonCode.NO_CARD)
    }
}
