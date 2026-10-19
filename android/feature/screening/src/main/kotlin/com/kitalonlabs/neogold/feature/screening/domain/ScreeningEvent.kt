package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.engine.camera.model.CaptureMetadata
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityGateResult

/**
 * Events that trigger transitions in [ScreeningStateMachine].
 */
sealed class ScreeningEvent {

    /**
     * Start the screening flow.
     */
    data object StartScreening : ScreeningEvent()

    /**
     * User submits clinical screening inputs.
     */
    data class SubmitInputs(
        val inputs: ScreeningInputs
    ) : ScreeningEvent()

    /**
     * Executes hard clinical rule evaluation against the triage engine.
     */
    data class EvaluateRules(
        val triageEngine: TriageEngine,
        val versions: VersionsRecord
    ) : ScreeningEvent()

    /**
     * Card QR scan outcome.
     */
    data class CardVerified(
        val verificationResult: VerificationResult<CardBatchPayload>
    ) : ScreeningEvent()

    /**
     * Frame captured from camera with quality gate evaluation.
     */
    data class FrameCaptured(
        val frame: ImageFrame,
        val metadata: CaptureMetadata? = null,
        val qualityResult: QualityGateResult,
        val fallbackVersions: VersionsRecord? = null
    ) : ScreeningEvent()

    /**
     * Color correction, feature extraction, estimation, and triage analysis finished.
     */
    data class AnalysisFinished(
        val intermediateData: ScreeningIntermediateData
    ) : ScreeningEvent()

    /**
     * Save the session record (called on terminal states or user confirmation).
     */
    data class SaveSession(
        val sessionId: String
    ) : ScreeningEvent()

    /**
     * Reset the screening state machine back to initial state.
     */
    data object Reset : ScreeningEvent()
}
