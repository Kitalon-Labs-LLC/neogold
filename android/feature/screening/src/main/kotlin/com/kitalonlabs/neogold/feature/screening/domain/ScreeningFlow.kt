package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker
import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.estimation.BodySite
import com.kitalonlabs.neogold.engine.estimation.EstimationEngine
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
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
@Suppress(
    "TooManyFunctions"
) // one method for each thing the screens ask of the flow: an answer, a decision, a photo, the row
class ScreeningFlow(
    private val integrityChecker: IntegrityChecker,
    private val triageEngine: TriageEngine?,
    private val estimationEngine: EstimationEngine?,
    private val baseVersions: VersionsRecord,
    private val pipeline: ScreeningPipelineUseCase,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = ANALYSIS_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
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
    },
    private val runForehead: (ForeheadAnalysis) -> PipelineExecutionResult = { run ->
        pipeline.addForehead(
            sternum = run.sternum,
            frame = run.frame,
            attempt = run.attempt,
            inputs = run.inputs,
            estimationEngine = run.estimationEngine,
            triageEngine = run.triageEngine
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

    /** What one forehead analysis is given: the sternum result it adds to, and the photo. */
    class ForeheadAnalysis(
        val sternum: ScreeningIntermediateData,
        val frame: ImageFrame,
        val attempt: Int,
        val inputs: ScreeningInputs,
        val estimationEngine: EstimationEngine,
        val triageEngine: TriageEngine
    )

    /** What adding the forehead photo came to. In every case but [Added] the result from the sternum photo stands. */
    sealed class ForeheadOutcome {
        /** Both sites were used and the engine decided again. */
        data object Added : ForeheadOutcome()

        /** The quality gate refused the photo; [reason] says why, and the worker may take it again or go on without it. */
        data class PhotoProblem(val reason: QualityReasonCode?) : ForeheadOutcome()

        /** The photo could not be used (too slow, or the analysis failed); the worker may go on without it. */
        data object NotUsed : ForeheadOutcome()
    }

    private val machine = ScreeningStateMachine(integrityChecker)

    // Analyses run here, detached from the caller, so that the caller can stop waiting on one that will not stop.
    private val analysisScope = CoroutineScope(SupervisorJob())

    val state: StateFlow<ScreeningState> get() = machine.state
    val currentState: ScreeningState get() = machine.currentState

    private var consent = false
    private var ageHours: Double? = null
    private var gestationalAgeWeeks: Int? = null
    private var earlyJaundice: Boolean? = null
    private var dangerSigns: Boolean? = null
    private var priorPhototherapy: Boolean? = null

    /** When the screening reached its decision: the time a recheck is counted from and the time the card shows. */
    var decidedAtMs: Long = 0L
        private set

    /** True once the row has been written, so a second tap on Save cannot write a second row. */
    var saved: Boolean = false
        private set

    /** How many photos have been analysed in this screening. */
    var photosTaken: Int = 0
        private set

    // the optional forehead photo (FR-15): tries so far, whether the worker has settled it, and why the last try was refused
    private var sternumAttempt = 1
    private var foreheadSettled = false

    /** How many forehead photos have been tried in this screening. */
    var foreheadAttempts: Int = 0
        private set

    /** Why the last forehead photo was refused, or null. */
    var foreheadProblem: QualityReasonCode? = null
        private set

    /** True from the moment the worker chooses to add a forehead photo until that photo has been analysed. */
    var awaitingForehead: Boolean = false
        private set

    /** Starts a new screening. [guardianConsentConfirmed] is the answer at S10a; without it the camera never opens. */
    fun begin(guardianConsentConfirmed: Boolean): ScreeningState {
        consent = guardianConsentConfirmed
        ageHours = null
        gestationalAgeWeeks = null
        earlyJaundice = null
        dangerSigns = null
        priorPhototherapy = null
        photosTaken = 0
        sternumAttempt = 1
        foreheadSettled = false
        foreheadAttempts = 0
        foreheadProblem = null
        awaitingForehead = false
        decidedAtMs = 0L
        saved = false
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
        return noteDecision(machine.dispatch(ScreeningEvent.EvaluateRules(engine, baseVersions)))
    }

    /**
     * Asks the engine as soon as an answer settles the outcome, so a baby with a danger sign is told "Refer urgently now"
     * without two more questions: danger signs (R1) outrank every other rule, and early jaundice (R2) outranks all but R1 once
     * the danger-signs answer is in. Rules R3 to R5 come after, so the answers not yet asked cannot change this outcome; they
     * are given to the engine as "no" for that call only and the saved row notes the rule that decided. Null when nothing is
     * settled yet.
     */
    fun decideEarly(): ScreeningState? {
        val engine = triageEngine
        val age = ageHours
        val weeks = gestationalAgeWeeks
        if (!outcomeIsSettled) return null
        if (engine == null || age == null || weeks == null) return null
        machine.dispatch(
            ScreeningEvent.SubmitInputs(
                ScreeningInputs(
                    gestationalAgeWeeks = weeks,
                    ageHours = age,
                    dangerSigns = dangerSigns == true,
                    jaundiceOnsetUnder24h = earlyJaundice == true,
                    priorPhototherapy = priorPhototherapy == true,
                    guardianConsentConfirmed = consent
                )
            )
        )
        return noteDecision(machine.dispatch(ScreeningEvent.EvaluateRules(engine, baseVersions)))
    }

    /** Danger signs, or early jaundice with the danger-signs answer in, decide the outcome whatever is asked later. */
    private val outcomeIsSettled: Boolean
        get() = dangerSigns == true || (earlyJaundice == true && dangerSigns != null)

    private fun noteDecision(state: ScreeningState): ScreeningState {
        if (state !is ScreeningState.Capture && state !is ScreeningState.CardCheck && state !is ScreeningState.Inputs) {
            decidedAtMs = clock()
        }
        return state
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
        noteDecision(machine.dispatch(ScreeningEvent.CardVerified(result)))

    /** How many photos have been tried, counting the one being taken: 1, 2 or 3. */
    val attempt: Int get() = (machine.currentState as? ScreeningState.Capture)?.attempt ?: 0

    /**
     * Analyses one photo. A photo the quality gate refuses sends the worker back to take another (up to three, then a blood
     * test); a photo that passes is carried through colour correction, the estimate and the triage decision.
     */
    @Suppress("ReturnCount") // each early exit is a different state: no engine, or not at the camera
    suspend fun analyze(frame: ImageFrame, metadata: CaptureMetadata?): ScreeningState {
        val capture = machine.currentState as? ScreeningState.Capture ?: return machine.currentState
        photosTaken++
        sternumAttempt = capture.attempt
        val engine = triageEngine
        val model = estimationEngine
        if (engine == null || model == null) return noteDecision(machine.dispatch(ScreeningEvent.AnalysisFailed))

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
        return noteDecision(finishAnalysis(withTimeLimit { runPipeline(run) }, frame, metadata))
    }

    /**
     * Runs [work] in its own job and waits for it with the time limit. The wait is cancellable even when the work is not, so
     * a computation that never checks for cancellation cannot keep the worker past the limit; its result is simply dropped
     * (NFR-02). Null when the work was too slow or threw.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // too slow, or failed: the callers fall back
    private suspend fun <T : Any> withTimeLimit(work: () -> T): T? {
        val job = analysisScope.async(cpu) { work() }
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (e: Exception) {
            null
        }
        if (outcome == null) job.cancel()
        return outcome
    }

    // ---- the optional forehead photo (FR-15, NG-040, TC-CAP-16) --------------------------------------------------------

    private val sternumResult: ScreeningState.Result?
        get() = (machine.currentState as? ScreeningState.Result)?.takeIf {
            it.intermediateData.estimationOutput.siteEstimates.keys == setOf(BodySite.STERNUM)
        }

    /** True when the model has a forehead site, so a forehead photo can be used at all. */
    private val modelHasForehead: Boolean
        get() = estimationEngine?.model?.let {
            BodySite.FOREHEAD in it.sites && BodySite.FOREHEAD in it.multiSiteWeights
        } == true

    /**
     * True while the result comes from the sternum photo alone and the worker has not yet chosen: S18 is shown instead of the
     * result. False when the model has no forehead site, so a photo is never taken that cannot be used.
     */
    val shouldOfferForehead: Boolean
        get() = !saved && !foreheadSettled && modelHasForehead && sternumResult != null

    /** True when another forehead photo may be taken: up to three, as for the sternum photo. */
    val canAddForehead: Boolean
        get() = shouldOfferForehead && foreheadAttempts < MAX_FOREHEAD_ATTEMPTS

    /** The worker chose not to add a forehead photo: the result from the sternum photo stands and is shown. */
    fun skipForehead() {
        if (!shouldOfferForehead) return
        foreheadSettled = true
        awaitingForehead = false
        decidedAtMs = clock()
    }

    /** The worker chose to add a forehead photo; the next photo analysed is the forehead's. False when none may be added. */
    fun startForeheadPhoto(): Boolean {
        if (!canAddForehead) return false
        awaitingForehead = true
        return true
    }

    /**
     * Analyses the forehead photo and, if it is good, makes the estimate and the decision again from both sites. The result
     * from the sternum photo is never made worse by this photo: a refused, failed or slow forehead photo leaves it as it was,
     * and the worker may try again or go on without it.
     */
    suspend fun addForehead(frame: ImageFrame): ForeheadOutcome {
        val run = foreheadRunFor(frame)
        if (run == null) {
            awaitingForehead = false
            return ForeheadOutcome.NotUsed
        }
        foreheadAttempts++
        val outcome = withTimeLimit { runForehead(run) }
        awaitingForehead = false // settled; a screen recreated before this point still knows the photo is a forehead's
        return when (outcome) {
            is PipelineExecutionResult.Success -> {
                foreheadProblem = null
                foreheadSettled = true
                machine.dispatch(ScreeningEvent.ForeheadAdded(outcome.intermediateData))
                decidedAtMs = clock()
                ForeheadOutcome.Added
            }
            is PipelineExecutionResult.QualityFailed -> {
                foreheadProblem = outcome.qualityResult.failureReasons.firstOrNull()
                ForeheadOutcome.PhotoProblem(foreheadProblem)
            }
            is PipelineExecutionResult.Error, null -> {
                foreheadProblem = null
                ForeheadOutcome.NotUsed
            }
        }
    }

    /** What to analyse for a forehead photo, or null when none may be added now or an engine is missing. */
    private fun foreheadRunFor(frame: ImageFrame): ForeheadAnalysis? {
        val sternum = sternumResult ?: return null
        val engine = triageEngine ?: return null
        val model = estimationEngine ?: return null
        if (!canAddForehead) return null
        return ForeheadAnalysis(sternum.intermediateData, frame, sternumAttempt, sternum.inputs, model, engine)
    }

    private fun finishAnalysis(outcome: PipelineExecutionResult?, frame: ImageFrame, metadata: CaptureMetadata?): ScreeningState =
        when (outcome) {
            is PipelineExecutionResult.QualityFailed ->
                machine.dispatch(ScreeningEvent.FrameCaptured(frame, metadata, outcome.qualityResult))
            is PipelineExecutionResult.Success -> {
                machine.dispatch(ScreeningEvent.FrameCaptured(frame, metadata, outcome.intermediateData.qualityResult))
                machine.dispatch(ScreeningEvent.AnalysisFinished(outcome.intermediateData))
            }
            // too slow, failed, or no answer: a blood test
            is PipelineExecutionResult.Error, null -> machine.dispatch(ScreeningEvent.AnalysisFailed)
        }

    /** The worker does not want to wait or retake: a blood test, decided by the engine. */
    fun giveUp(): ScreeningState = noteDecision(machine.dispatch(ScreeningEvent.AnalysisFailed))

    /**
     * The row for the log, once the screening has ended; null before that, and null after the row was taken once (a second
     * tap on Save writes nothing). A recheck is due the engine's hours after the decision was shown, not after Save was tapped.
     */
    fun record(
        sessionId: String,
        nowMs: Long,
        registerNumber: String?,
        deviceProfileId: String?,
        captureAttempts: Int = photosTaken
    ): ScreeningSessionEntity? {
        // nothing is written while the worker has still to choose whether to add a forehead photo
        if (saved || shouldOfferForehead) return null
        val row = machine.currentState.toSessionEntity(
            sessionId = sessionId,
            nowMs = nowMs,
            registerNumber = registerNumber,
            deviceProfileId = deviceProfileId,
            captureAttempts = captureAttempts,
            decidedAtMs = decidedAtMs.takeIf { it > 0L } ?: nowMs
        )
        if (row != null) saved = true
        return row
    }

    fun reset() {
        machine.dispatch(ScreeningEvent.Reset)
    }

    companion object {
        /** The analysis may take this long before the worker is sent to a blood test (NFR-02: p95 under 10 s). */
        const val ANALYSIS_TIMEOUT_MS = 10_000L

        /** A forehead photo may be tried this many times, then the worker goes on without it. */
        const val MAX_FOREHEAD_ATTEMPTS = 3
    }
}
