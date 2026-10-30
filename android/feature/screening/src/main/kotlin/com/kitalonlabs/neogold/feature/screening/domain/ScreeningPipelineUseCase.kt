package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker
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
import com.kitalonlabs.neogold.engine.estimation.EstimationOutput
import com.kitalonlabs.neogold.engine.estimation.SingleSiteEstimate
import com.kitalonlabs.neogold.engine.estimation.SiteCapture
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageInputs
import com.kitalonlabs.neogold.engine.triage.TriageResult
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
    private val integrityChecker: IntegrityChecker,
    private val visionEngine: VisionEngine = VisionEngine()
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
     * Executes the full pipeline on a captured image frame: the sternum photo alone (the forehead photo is added afterwards,
     * and only when the worker chooses to, by [addForehead]).
     */
    @Suppress("TooGenericExceptionCaught") // the pipeline reports any failure as PipelineExecutionResult.Error
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
            when (val read = readSite(frame, cardBatch, timings)) {
                is SiteRead.Refused -> PipelineExecutionResult.QualityFailed(read.qualityResult)
                is SiteRead.Ok -> {
                    val reading = read.reading
                    // Stage 3: Model & Triage (< 200 ms budget)
                    val intermediateData = traceSection("Screening_QualityGateAndAnalysis") {
                        val startAnalysis = System.nanoTime()
                        val conclusion = conclude(
                            sternumLab = reading.correctedWindowLab,
                            foreheadLab = null,
                            inputs = inputs,
                            attempt = attempt,
                            estimationEngine = estimationEngine,
                            triageEngine = triageEngine
                        )
                        timings["quality_and_analysis_ms"] = (System.nanoTime() - startAnalysis) / NANOS_PER_MILLI
                        timings["total_capture_to_result_ms"] = (System.nanoTime() - startOverall) / NANOS_PER_MILLI

                        ScreeningIntermediateData(
                            cardPayload = cardBatch,
                            captureMetadata = captureMetadata,
                            qualityResult = reading.qualityResult,
                            rawWindowRgb = reading.rawWindowRgb,
                            linearizedWindowRgb = reading.linearizedWindowRgb,
                            ccmMatrix = reading.ccmMatrix,
                            correctedWindowLab = reading.correctedWindowLab,
                            extractedFeatures = conclusion.singleSite.rawFeatures,
                            standardizedFeatures = conclusion.singleSite.standardizedFeatures.toList(),
                            singleSiteEstimate = conclusion.singleSite,
                            estimationOutput = conclusion.output,
                            triageResult = conclusion.triage,
                            versions = versions,
                            stageTimingsMs = timings
                        )
                    }
                    PipelineExecutionResult.Success(intermediateData)
                }
            }
        } catch (t: Throwable) {
            PipelineExecutionResult.Error(t)
        }
    }

    /**
     * Adds the optional forehead photo to a screening that already has a result from the sternum photo (FR-15, NG-040,
     * TC-CAP-16). The forehead photo is read like the first one (its own card, its own colour correction and quality gate);
     * the estimate is then made from both sites and the triage engine decides again. A photo the quality gate refuses is
     * [PipelineExecutionResult.QualityFailed]; the sternum result is left as it was, because the forehead photo is optional
     * and a failed one must never turn a result into a blood test.
     */
    @Suppress("TooGenericExceptionCaught") // the pipeline reports any failure as PipelineExecutionResult.Error
    fun addForehead(
        sternum: ScreeningIntermediateData,
        frame: ImageFrame,
        attempt: Int,
        inputs: ScreeningInputs,
        estimationEngine: EstimationEngine,
        triageEngine: TriageEngine
    ): PipelineExecutionResult = traceSection("Screening_CaptureToResult") {
        val timings = sternum.stageTimingsMs.toMutableMap()
        val startOverall = System.nanoTime()

        if (!integrityChecker.isScreeningAllowed()) {
            return@traceSection PipelineExecutionResult.Error(
                SecurityException("Screening disabled: clinical asset integrity compromised")
            )
        }

        try {
            when (val read = readSite(frame, sternum.cardPayload, timings, key = "forehead_decode_warp_sample_ms")) {
                is SiteRead.Refused -> PipelineExecutionResult.QualityFailed(read.qualityResult)
                is SiteRead.Ok -> {
                    val conclusion = traceSection("Screening_QualityGateAndAnalysis") {
                        conclude(
                            sternumLab = sternum.correctedWindowLab,
                            foreheadLab = read.reading.correctedWindowLab,
                            inputs = inputs,
                            attempt = attempt,
                            estimationEngine = estimationEngine,
                            triageEngine = triageEngine
                        )
                    }
                    timings["forehead_total_ms"] = (System.nanoTime() - startOverall) / NANOS_PER_MILLI
                    // A second photo may only raise the level of care: if the two-site decision is less careful than the
                    // one from the chest photo, the chest result is kept (safety review of BUG-2026-090). The weights and the
                    // two-site interval have not been validated on babies, and any photo that passes the quality gate is
                    // averaged in, so it must not be able to talk a worker out of a blood test.
                    if (isAtLeastAsCareful(conclusion.triage, sternum.triageResult)) {
                        PipelineExecutionResult.Success(
                            sternum.copy(
                                estimationOutput = conclusion.output,
                                triageResult = conclusion.triage,
                                stageTimingsMs = timings
                            )
                        )
                    } else {
                        PipelineExecutionResult.Success(sternum.copy(stageTimingsMs = timings))
                    }
                }
            }
        } catch (t: Throwable) {
            PipelineExecutionResult.Error(t)
        }
    }

    /** The reference colour of each card patch, by patch id, from the verified batch. */
    private fun referenceColours(
        cardBatch: CardBatchPayload
    ): Map<String, Lab> = cardBatch.patches.associate { patchRef ->
        val id = if (patchRef.index in PATCH_IDS_IN_ORDER.indices) {
            PATCH_IDS_IN_ORDER[patchRef.index]
        } else {
            "P${patchRef.index}"
        }
        id to Lab(patchRef.lStar, patchRef.aStar, patchRef.bStar)
    }

    /**
     * Reads one photo: decode, warp, sample and the quality gate (doc 4 Section 5.6: < 700 ms), then the colour correction
     * against the card, to the skin colour in CIELAB. Throws when a stage cannot run; the callers turn that into
     * [PipelineExecutionResult.Error].
     */
    private fun readSite(
        frame: ImageFrame,
        cardBatch: CardBatchPayload,
        timings: MutableMap<String, Long>,
        key: String = "decode_warp_sample_ms"
    ): SiteRead {
        val batchRefMap = referenceColours(cardBatch)

        val startVision = System.nanoTime()
        val qualityResult: QualityGateResult = traceSection("Screening_DecodeWarpSample") {
            visionEngine.processFrame(frame, batchRefMap)
        }
        timings[key] = (System.nanoTime() - startVision) / NANOS_PER_MILLI

        // Quality Gate check: if failed, the photo is refused
        if (!qualityResult.passed) return SiteRead.Refused(qualityResult)

        val sampledPatches = qualityResult.sampledPatches
            ?: error("Quality gate passed but sampled patches are null")
        val rawSkinRgb = qualityResult.windowRgb
            ?: error("Quality gate passed but window RGB is null")

        // 1. Monotonic gray-ramp linearization
        val observedGrays = CardGeometry.GRAY_RAMP_PATCH_IDS.map { patchId ->
            // a gray patch that was not sampled is a failed analysis (a blood test), never a made-up gray
            sampledPatches[patchId] ?: error("Gray patch $patchId was not sampled")
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

        return SiteRead.Ok(
            SiteReading(
                qualityResult = qualityResult,
                rawWindowRgb = rawSkinRgb,
                linearizedWindowRgb = linearizedSkinRgb,
                ccmMatrix = listOf(
                    listOf(ccm.matrix[0, 0], ccm.matrix[0, 1], ccm.matrix[0, 2]),
                    listOf(ccm.matrix[1, 0], ccm.matrix[1, 1], ccm.matrix[1, 2]),
                    listOf(ccm.matrix[2, 0], ccm.matrix[2, 1], ccm.matrix[2, 2])
                ),
                correctedWindowLab = correctedSkinLab
            )
        )
    }

    /**
     * The estimate from one site or two, and the triage decision on it (hard rules were checked before the camera opened;
     * this evaluates the threshold and the interval).
     */
    private fun conclude(
        sternumLab: Lab,
        foreheadLab: Lab?,
        inputs: ScreeningInputs,
        attempt: Int,
        estimationEngine: EstimationEngine,
        triageEngine: TriageEngine
    ): Conclusion {
        val singleSite = estimationEngine.estimateSite(BodySite.STERNUM, sternumLab)
        val captures = listOfNotNull(
            SiteCapture(BodySite.STERNUM, sternumLab),
            foreheadLab?.let { SiteCapture(BodySite.FOREHEAD, it) }
        )
        val estimationOutput = estimationEngine.estimate(captures)
        val triageResult = triageEngine.evaluate(
            TriageInputs(
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
        )
        return Conclusion(singleSite, estimationOutput, triageResult)
    }

    /** What one photo gave: the quality verdict and the skin colour, corrected against the card. */
    private class SiteReading(
        val qualityResult: QualityGateResult,
        val rawWindowRgb: Rgb,
        val linearizedWindowRgb: Rgb,
        val ccmMatrix: List<List<Double>>,
        val correctedWindowLab: Lab
    )

    private sealed class SiteRead {
        class Ok(val reading: SiteReading) : SiteRead()
        class Refused(val qualityResult: QualityGateResult) : SiteRead()
    }

    private class Conclusion(
        val singleSite: SingleSiteEstimate,
        val output: EstimationOutput,
        val triage: TriageResult
    )
}

/**
 * True when [candidate] asks for at least as much care as [reference]: the same action or a more urgent one, and for a recheck
 * the same time or a sooner one. A recheck that carries no hours counts as the most urgent recheck, as it is shown and stored
 * as a blood test. A decision that is not an action (triage unavailable) is never more careful than an action.
 */
internal fun isAtLeastAsCareful(candidate: TriageResult, reference: TriageResult): Boolean {
    if (candidate !is TriageResult.Action || reference !is TriageResult.Action) return candidate == reference
    // TriageAction is declared from the most urgent action to the least
    return when {
        candidate.action.ordinal != reference.action.ordinal -> candidate.action.ordinal < reference.action.ordinal
        candidate.action == TriageAction.RECHECK -> (candidate.recheckHours ?: 0) <= (reference.recheckHours ?: 0)
        else -> true
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
