package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One screening, from the first answer to the saved record (package E1). It holds what the worker has answered, with no
 * value filled in for an answer not given, and drives [ScreeningStateMachine]; every clinical decision is the triage
 * engine's. The screens render [state] and call these methods; they decide nothing.
 *
 * A photo is analysed on [cpu] with a time limit of [timeoutMs] (10 seconds, NFR-02): if the analysis does not finish, or
 * fails, the worker is sent to a blood test, which the engine decides like the quality fallback. A photo is only ever held in
 * memory here; nothing is written to disk (doc 8 Section 2).
 *
 * @param triageEngine the engine over the verified config; null when there is none, and then no screening starts
 * @param estimationEngine the engine over the verified model
 * @param baseVersions the versions of this phone and build, with `cardBatchId` set to [NO_CARD_SCANNED]
 * @param runPipeline the analysis; the real pipeline unless a test replaces it
 */
class ScreeningFlow(
    private val integrityChecker: IntegrityChecker,
    private val triageEngine: TriageEngine?,
    private val estimationEngine: EstimationEngine?,
    private val baseVersions: VersionsRecord,
    private val pipeline: ScreeningPipelineUseCase,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = ANALYSIS_TIMEOUT_MS,
    private val runPipeline: (Analysis) -> PipelineExecutionResult = { run ->
        pipeline.execute(
            frame = run.frame,
            cardBatch = run.cardBatch,
            inputs = run.inputs,
            attempt = run.attempt,
            estimationEngine = run.estimationEngine,
            triageEngine = run.triageEngine,
            versions = run.versions,
            captureMetadata = run.metadata
        )
    }
) {
    /** What one analysis is given. */
    class Analysis(
        val frame: ImageFrame,
        val metadata: CaptureMetadata?,
        val cardBatch: CardBatchPayload,
        val inputs: ScreeningInputs,
        val attempt: Int,
        val estimationEngine: EstimationEngine,
        val triageEngine: TriageEngine,
        val versions: VersionsRecord
    )

    private val machine = ScreeningStateMachine(integrityChecker)

    val state: StateFlow<ScreeningState> get() = machine.state
    val currentState: ScreeningState get() = machine.currentState

    private var consent = false
    private var ageHours: Double? = null
    private var gestationalAgeWeeks: Int? = null
    private var earlyJaundice: Boolean? = null
    private var dangerSigns: Boolean? = null
    private var priorPhototherapy: Boolean? = null

    /** Starts a new screening. [guardianConsentConfirmed] is the answer at S10a; without it the camera never opens. */
    fun begin(guardianConsentConfirmed: Boolean): ScreeningState {
        consent = guardianConsentConfirmed
        ageHours = null
        gestationalAgeWeeks = null
        earlyJaundice = null
        dangerSigns = null
        priorPhototherapy = null
        machine.dispatch(ScreeningEvent.Reset)
        return machine.dispatch(ScreeningEvent.StartScreening)
    }

    fun answerAgeAndGestation(ageHours: Double, gestationalAgeWeeks: Int) {
        this.ageHours = ageHours
        this.gestationalAgeWeeks = gestationalAgeWeeks
    }

    fun answerEarlyJaundice(yes: Boolean) {
        earlyJaundice = yes
    }

    fun answerDangerSigns(yes: Boolean) {
        dangerSigns = yes
    }

    fun answerPriorPhototherapy(yes: Boolean) {
        priorPhototherapy = yes
    }

    /** True when every answer the engine needs has been given. */
    val isComplete: Boolean
        get() = ageHours != null && gestationalAgeWeeks != null && earlyJaundice != null &&
            dangerSigns != null && priorPhototherapy != null

    /**
     * Hands every answer to the machine and asks the engine whether a hard rule decides or the camera may open. With an answer
     * missing, nothing is decided and the state does not move.
     */
    fun decide(): ScreeningState {
        val engine = triageEngine
        val inputs = answeredInputs()
        if (engine == null || inputs == null) return machine.currentState
        machine.dispatch(ScreeningEvent.SubmitInputs(inputs))
        return machine.dispatch(ScreeningEvent.EvaluateRules(engine, baseVersions))
    }

    /** The answers as the engine's inputs, or null while any is missing. */
    private fun answeredInputs(): ScreeningInputs? {
        val answers = listOf(earlyJaundice, dangerSigns, priorPhototherapy)
        val age = ageHours
        val weeks = gestationalAgeWeeks
        if (age == null || weeks == null || answers.any { it == null }) return null
        return ScreeningInputs(
            gestationalAgeWeeks = weeks,
            ageHours = age,
            dangerSigns = dangerSigns == true,
            jaundiceOnsetUnder24h = earlyJaundice == true,
            priorPhototherapy = priorPhototherapy == true,
            guardianConsentConfirmed = consent
        )
    }

    /** The result of checking the card the phone works with. */
    fun cardChecked(result: VerificationResult<CardBatchPayload>): ScreeningState =
        machine.dispatch(ScreeningEvent.CardVerified(result))

    /** How many photos have been tried, counting the one being taken: 1, 2 or 3. */
    val attempt: Int get() = (machine.currentState as? ScreeningState.Capture)?.attempt ?: 0

    /**
     * Analyses one photo. A photo the quality gate refuses sends the worker back to take another (up to three, then a blood
     * test); a photo that passes is carried through colour correction, the estimate and the triage decision.
     */
    @Suppress(
        "ReturnCount",
        "TooGenericExceptionCaught",
        "SwallowedException"
    ) // a failed analysis is a blood test, never a crash
    suspend fun analyze(frame: ImageFrame, metadata: CaptureMetadata?): ScreeningState {
        val capture = machine.currentState as? ScreeningState.Capture ?: return machine.currentState
        val engine = triageEngine
        val model = estimationEngine
        if (engine == null || model == null) return machine.dispatch(ScreeningEvent.AnalysisFailed)

        val run = Analysis(
            frame = frame,
            metadata = metadata,
            cardBatch = capture.cardBatch,
            inputs = capture.inputs,
            attempt = capture.attempt,
            estimationEngine = model,
            triageEngine = engine,
            versions = baseVersions.copy(cardBatchId = capture.cardBatch.batchId)
        )
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { withContext(cpu) { runPipeline(run) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when (outcome) {
            is PipelineExecutionResult.QualityFailed ->
                machine.dispatch(ScreeningEvent.FrameCaptured(frame, metadata, outcome.qualityResult))
            is PipelineExecutionResult.Success -> {
                machine.dispatch(ScreeningEvent.FrameCaptured(frame, metadata, outcome.intermediateData.qualityResult))
                machine.dispatch(ScreeningEvent.AnalysisFinished(outcome.intermediateData))
            }
            // too slow, failed, or no answer: a blood test
            is PipelineExecutionResult.Error, null -> machine.dispatch(ScreeningEvent.AnalysisFailed)
        }
    }

    /** The row for the log, once the screening has ended; null before that. */
    fun record(
        sessionId: String,
        nowMs: Long,
        registerNumber: String?,
        deviceProfileId: String?,
        captureAttempts: Int
    ): com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity? =
        machine.currentState.toSessionEntity(sessionId, nowMs, registerNumber, deviceProfileId, captureAttempts)

    fun reset() {
        machine.dispatch(ScreeningEvent.Reset)
    }

    companion object {
        /** The analysis may take this long before the worker is sent to a blood test (NFR-02: p95 under 10 s). */
        const val ANALYSIS_TIMEOUT_MS = 10_000L
    }
}
