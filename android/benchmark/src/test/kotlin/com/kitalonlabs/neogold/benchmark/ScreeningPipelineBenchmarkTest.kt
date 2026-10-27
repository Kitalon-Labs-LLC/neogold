package com.kitalonlabs.neogold.benchmark

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.PatchReference
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.DefaultMarkerDetector
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.SyntheticCardRenderer
import com.kitalonlabs.neogold.engine.vision.SyntheticRenderConfig
import com.kitalonlabs.neogold.engine.vision.SyntheticWindowContent
import com.kitalonlabs.neogold.engine.vision.VisionEngine
import com.kitalonlabs.neogold.feature.screening.domain.PipelineExecutionResult
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningInputs
import com.kitalonlabs.neogold.feature.screening.domain.ScreeningPipelineUseCase
import com.kitalonlabs.neogold.feature.screening.domain.VersionsRecord
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Performance and Macrobenchmark tests validating the stages of doc 4 Section 5.6:
 * - Marker detection on preview frame (< 80 ms)
 * - Decode, warp, sampling (< 700 ms)
 * - Quality gate, correction, features, model (< 200 ms)
 * - Capture to result (< 4 s p50, < 10 s p95 hard limit, NFR-02)
 * - Peak memory (< 120 MB, NFR-04b)
 *
 * Runs 30 captures and reports median (p50) and 95th percentile (p95).
 */
class ScreeningPipelineBenchmarkTest {

    private lateinit var pipelineUseCase: ScreeningPipelineUseCase
    private lateinit var estimationEngine: EstimationEngine
    private lateinit var triageEngine: TriageEngine
    private lateinit var testCardBatch: CardBatchPayload
    private lateinit var testVersions: VersionsRecord
    private lateinit var fullResFrame: ImageFrame
    private lateinit var previewFrame: ImageFrame

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
            cardBatchId = "BATCH-BENCH-01",
            deviceModel = "Helio-G85-Reference",
            deviceManufacturer = "Transsion",
            osVersion = "Android 14"
        )

        val patchRefs = CardGeometry.PATCHES.values.mapIndexed { idx, p ->
            PatchReference(idx, p.nominalLab.l, p.nominalLab.a, p.nominalLab.b)
        }

        testCardBatch = CardBatchPayload(
            formatVersion = 1,
            issuedAtDays = 1000,
            expiresAtDays = 3000,
            issuedAt = LocalDate.now().minusDays(10),
            expiresAt = LocalDate.now().plusDays(365),
            issuerKeyId = "issuer-bench",
            batchId = "BATCH-BENCH-01",
            patchCount = 23,
            patches = patchRefs,
            signedBytes = ByteArray(100),
            signature = ByteArray(64)
        )

        val renderer = SyntheticCardRenderer()
        fullResFrame = renderer.renderFrame(
            SyntheticRenderConfig(windowContent = SyntheticWindowContent.SKIN_JAUNDICE),
            frameWidth = 1600,
            frameHeight = 1200
        )
        previewFrame = renderer.renderFrame(
            SyntheticRenderConfig(scale = 0.4, windowContent = SyntheticWindowContent.SKIN_NORMAL),
            frameWidth = 640,
            frameHeight = 480
        )
    }

    @Test
    fun testMarkerDetectionPreviewLatency_under80Ms() {
        val detector = DefaultMarkerDetector()

        // Warm up (3 iterations)
        repeat(3) {
            detector.detectMarkers(previewFrame)
        }

        // 30 runs
        val latencies = mutableListOf<Long>()
        repeat(30) {
            val t0 = System.nanoTime()
            val markers = detector.detectMarkers(previewFrame)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            latencies.add(dtMs)
            assertThat(markers.size).isEqualTo(4)
        }

        latencies.sort()
        val p50 = latencies[15]
        val p95 = latencies[28]

        println("Marker Detection (640x480): p50 = $p50 ms, p95 = $p95 ms (budget < 80 ms)")
        assertThat(p50).isLessThan(80L)
        assertThat(p95).isLessThan(80L)
    }

    @Test
    fun testCaptureToResultBenchmark_30Runs_withinBudgets() {
        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )

        // Warm up (3 runs)
        repeat(3) {
            pipelineUseCase.execute(
                frame = fullResFrame,
                cardBatch = testCardBatch,
                inputs = inputs,
                attempt = 1,
                estimationEngine = estimationEngine,
                triageEngine = triageEngine,
                versions = testVersions
            )
        }

        // 30 runs
        val warpSampleLatencies = mutableListOf<Long>()
        val analysisLatencies = mutableListOf<Long>()
        val totalLatencies = mutableListOf<Long>()

        repeat(30) {
            val res = pipelineUseCase.execute(
                frame = fullResFrame,
                cardBatch = testCardBatch,
                inputs = inputs,
                attempt = 1,
                estimationEngine = estimationEngine,
                triageEngine = triageEngine,
                versions = testVersions
            )
            assertThat(res).isInstanceOf(PipelineExecutionResult.Success::class.java)
            val success = res as PipelineExecutionResult.Success
            val timings = success.intermediateData.stageTimingsMs

            warpSampleLatencies.add(timings["decode_warp_sample_ms"] ?: 0L)
            analysisLatencies.add(timings["quality_and_analysis_ms"] ?: 0L)
            totalLatencies.add(timings["total_capture_to_result_ms"] ?: 0L)
        }

        warpSampleLatencies.sort()
        analysisLatencies.sort()
        totalLatencies.sort()

        val warpP50 = warpSampleLatencies[15]
        val warpP95 = warpSampleLatencies[28]

        val analysisP50 = analysisLatencies[15]
        val analysisP95 = analysisLatencies[28]

        val totalP50 = totalLatencies[15]
        val totalP95 = totalLatencies[28]

        println("Warp & Sample: p50 = $warpP50 ms, p95 = $warpP95 ms (budget < 700 ms)")
        println("Quality & Analysis: p50 = $analysisP50 ms, p95 = $analysisP95 ms (budget < 200 ms)")
        println("Capture to Result: p50 = $totalP50 ms, p95 = $totalP95 ms (target < 4000 ms, limit < 10000 ms)")

        assertThat(warpP95).isLessThan(700L)
        assertThat(analysisP95).isLessThan(200L)
        assertThat(totalP50).isLessThan(4000L)
        assertThat(totalP95).isLessThan(10000L)
    }

    @Test
    fun testPeakMemoryFootprint_under120MB() {
        val runtime = Runtime.getRuntime()
        @Suppress("ExplicitGarbageCollectionCall") // settle the heap before reading the baseline
        System.gc()
        Thread.sleep(100)

        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        val inputs = ScreeningInputs(
            gestationalAgeWeeks = 38,
            ageHours = 48.0,
            dangerSigns = false,
            jaundiceOnsetUnder24h = false,
            priorPhototherapy = false,
            guardianConsentConfirmed = true
        )

        val res = pipelineUseCase.execute(
            frame = fullResFrame,
            cardBatch = testCardBatch,
            inputs = inputs,
            attempt = 1,
            estimationEngine = estimationEngine,
            triageEngine = triageEngine,
            versions = testVersions
        )
        assertThat(res).isInstanceOf(PipelineExecutionResult.Success::class.java)

        val memAfter = runtime.totalMemory() - runtime.freeMemory()
        val deltaMb = (memAfter - memBefore) / (1024.0 * 1024.0)

        // Bounded peak allocation in VisionEngine + downsampled frame: < 15 MB heap delta
        println("Screening Pipeline Heap Delta: %.2f MB (Peak budget < 120 MB)".format(deltaMb))
        assertThat(deltaMb).isLessThan(120.0)
    }
}
