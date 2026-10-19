package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.colorscience.ColorConversions
import com.kitalonlabs.neogold.engine.colorscience.ColorCorrectionFitter
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.colorscience.Linear3x3CorrectionModel
import com.kitalonlabs.neogold.engine.colorscience.PerChannelLinearizer
import com.kitalonlabs.neogold.engine.colorscience.Rgb
import com.kitalonlabs.neogold.engine.colorscience.Xyz
import com.kitalonlabs.neogold.engine.estimation.BodySite
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.estimation.SiteCapture
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageInputs
import com.kitalonlabs.neogold.engine.vision.CardGeometry
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.VisionEngine

/**
 * Result of executing the screening pipeline use case.
 */
sealed class PipelineExecutionResult {
    data class Success(val intermediateData: ScreeningIntermediateData) : PipelineExecutionResult()
    data class QualityFailed(val qualityResult: QualityGateResult) : PipelineExecutionResult()
    data class Error(val exception: Throwable) : PipelineExecutionResult()
}

/**
 * Domain-layer pipeline use case wiring:
 * Card trust -> Camera burst & decode -> Vision & quality gate ->
 * Color science (linearization & 3x3 CCM) -> Feature extraction ->
 * Bilirubin estimation -> Triage decision.
 *
 * Implements performance tracing sections per docs/4_System_Architecture.md Section 5.6:
 * - Screening_MarkerDetection
 * - Screening_AeAwbConvergence
 * - Screening_BurstCapture
 * - Screening_DecodeWarpSample
 * - Screening_QualityGateAndAnalysis
 * - Screening_CaptureToResult
 */
class ScreeningPipelineUseCase(
    private val visionEngine: VisionEngine = VisionEngine(),
    private val integrityChecker: com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker = com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker { true }
) {

    companion object {
        val PATCH_IDS_IN_ORDER = listOf(
            "G1", "G2", "G3", "G4", "G5", "G6", "G7",
            "K1", "K2", "K3", "K4", "K5", "K6", "K7", "K8", "K9",
            "X1", "X2", "X3",
            "G2D", "G4D", "K5D", "K8D"
        )

        // Measured spectral reflectances for Card v1 gray ramp (G1 to G7)
        val GRAY_RAMP_REFLECTANCES = listOf(0.763, 0.515, 0.328, 0.192, 0.106, 0.055, 0.025)

        inline fun <T> traceSection(name: String, block: () -> T): T {
            try {
                android.os.Trace.beginSection(name)
            } catch (_: Throwable) {
                // Ignore when running on standard JVM
            }
            try {
                return block()
            } finally {
                try {
                    android.os.Trace.endSection()
                } catch (_: Throwable) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Executes the full pipeline on a captured image frame.
     */
    fun execute(
        frame: ImageFrame,
        cardBatch: CardBatchPayload,
        inputs: ScreeningInputs,
        attempt: Int,
        estimationEngine: EstimationEngine,
        triageEngine: TriageEngine,
        versions: VersionsRecord,
        captureMetadata: CaptureMetadata? = null
    ): PipelineExecutionResult = traceSection("Screening_CaptureToResult") {
        val timings = mutableMapOf<String, Long>()
        val startOverall = System.nanoTime()

        if (!integrityChecker.isScreeningAllowed()) {
            return@traceSection PipelineExecutionResult.Error(
                SecurityException("Screening disabled: clinical asset integrity compromised")
            )
        }

        try {
            // Stage 1: Decode, Warp, Sampling & Marker Detection (doc 4 Section 5.6: < 700 ms)
            val batchRefMap = cardBatch.patches.associate { patchRef ->
                val id = if (patchRef.index in PATCH_IDS_IN_ORDER.indices) {
                    PATCH_IDS_IN_ORDER[patchRef.index]
                } else {
                    "P${patchRef.index}"
                }
                id to Lab(patchRef.lStar, patchRef.aStar, patchRef.bStar)
            }

            val startVision = System.nanoTime()
            val qualityResult: QualityGateResult = traceSection("Screening_DecodeWarpSample") {
                visionEngine.processFrame(frame, batchRefMap)
            }
            val visionDurationMs = (System.nanoTime() - startVision) / 1_000_000
            timings["decode_warp_sample_ms"] = visionDurationMs

            // Quality Gate check: if failed, return QualityFailed
            if (!qualityResult.passed) {
                return@traceSection PipelineExecutionResult.QualityFailed(qualityResult)
            }

            // Stage 2 & 3: Color Correction, Features, Model & Triage (< 200 ms budget)
            val intermediateData = traceSection("Screening_QualityGateAndAnalysis") {
                val startAnalysis = System.nanoTime()

                val sampledPatches = qualityResult.sampledPatches
                    ?: throw IllegalStateException("Quality gate passed but sampled patches are null")
                val rawSkinRgb = qualityResult.windowRgb
                    ?: throw IllegalStateException("Quality gate passed but window RGB is null")

                // 1. Monotonic gray-ramp linearization
                val observedGrays = CardGeometry.GRAY_RAMP_PATCH_IDS.map { patchId ->
                    sampledPatches[patchId] ?: Rgb(0.5, 0.5, 0.5)
                }
                val linearizer = PerChannelLinearizer.fit(observedGrays, GRAY_RAMP_REFLECTANCES)

                // 2. Linearize all observed patches
                val observedLinearRgbList = mutableListOf<Rgb>()
                val targetXyzList = mutableListOf<Xyz>()
                for ((id, sampledRgb) in sampledPatches) {
                    val targetLab = batchRefMap[id] ?: continue
                    observedLinearRgbList.add(linearizer.linearize(sampledRgb))
                    targetXyzList.add(ColorConversions.labToXyz(targetLab))
                }

                // 3. Fit 3x3 Color Correction Matrix (least squares)
                val ccm = ColorCorrectionFitter.fit(
                    linearRgbPatches = observedLinearRgbList,
                    referenceXyzPatches = targetXyzList,
                    useRootPolynomial = false
                ) as Linear3x3CorrectionModel

                // 4. Correct skin window pixels and convert to CIELAB
                val linearizedSkinRgb = linearizer.linearize(rawSkinRgb)
                val correctedSkinXyz = ccm.correct(linearizedSkinRgb)
                val correctedSkinLab = ColorConversions.xyzToLab(correctedSkinXyz)

                // 5. Estimation inference (using split conformal interval)
                val singleSite = estimationEngine.estimateSite(BodySite.STERNUM, correctedSkinLab)
                val estimationOutput = estimationEngine.estimate(
                    listOf(SiteCapture(BodySite.STERNUM, correctedSkinLab))
                )

                // 6. Triage evaluation (hard rules already checked, now evaluate threshold & intervals)
                val triageInputs = TriageInputs(
                    gestationalAgeWeeks = inputs.gestationalAgeWeeks,
                    ageHours = inputs.ageHours,
                    dangerSigns = inputs.dangerSigns,
                    jaundiceOnsetUnder24h = inputs.jaundiceOnsetUnder24h,
                    priorPhototherapy = inputs.priorPhototherapy,
                    estimate = estimationOutput.estimate,
                    lowerBound = estimationOutput.lowerBound,
                    upperBound = estimationOutput.upperBound,
                    qualityFailedAttempts = attempt - 1
                )
                val triageResult = triageEngine.evaluate(triageInputs)

                val analysisDurationMs = (System.nanoTime() - startAnalysis) / 1_000_000
                timings["quality_and_analysis_ms"] = analysisDurationMs
                timings["total_capture_to_result_ms"] = (System.nanoTime() - startOverall) / 1_000_000

                val ccmMatrixList = listOf(
                    listOf(ccm.matrix[0, 0], ccm.matrix[0, 1], ccm.matrix[0, 2]),
                    listOf(ccm.matrix[1, 0], ccm.matrix[1, 1], ccm.matrix[1, 2]),
                    listOf(ccm.matrix[2, 0], ccm.matrix[2, 1], ccm.matrix[2, 2])
                )

                ScreeningIntermediateData(
                    cardPayload = cardBatch,
                    captureMetadata = captureMetadata,
                    qualityResult = qualityResult,
                    rawWindowRgb = rawSkinRgb,
                    linearizedWindowRgb = linearizedSkinRgb,
                    ccmMatrix = ccmMatrixList,
                    correctedWindowLab = correctedSkinLab,
                    extractedFeatures = singleSite.rawFeatures,
                    standardizedFeatures = singleSite.standardizedFeatures.toList(),
                    singleSiteEstimate = singleSite,
                    estimationOutput = estimationOutput,
                    triageResult = triageResult,
                    versions = versions,
                    stageTimingsMs = timings
                )
            }

            PipelineExecutionResult.Success(intermediateData)
        } catch (t: Throwable) {
            PipelineExecutionResult.Error(t)
        }
    }
}
