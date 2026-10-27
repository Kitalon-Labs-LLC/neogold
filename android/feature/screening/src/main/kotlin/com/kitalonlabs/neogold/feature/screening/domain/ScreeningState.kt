package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.triage.TriageResult
import com.kitalonlabs.neogold.engine.vision.QualityGateResult
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode

/**
 * Screening state machine states specified in docs/4_System_Architecture.md Section 4.4.
 * The UI renders state only; it never decides clinical flow.
 */
sealed class ScreeningState {

    /**
     * Initial unstarted state.
     */
    data object Initial : ScreeningState()

    /**
     * Inputs collection screen (gestational age, birth time / age, safety questions, consent).
     */
    data class Inputs(
        val inputs: ScreeningInputs? = null
    ) : ScreeningState()

    /**
     * Evaluating hard clinical rules (R1 to R5) before camera opens.
     */
    data class RuleCheck(
        val inputs: ScreeningInputs
    ) : ScreeningState()

    /**
     * Terminal state: Hard rule R1 (danger signs) or R2 (early jaundice < 24h) triggered.
     * Urgent hospital referral required immediately.
     */
    data class UrgentReferral(
        val inputs: ScreeningInputs,
        val reasonCode: String,
        val triageResult: TriageResult,
        val versions: VersionsRecord
    ) : ScreeningState()

    /**
     * Terminal state: Hard rule R3 (GA < 35 wk), R4 (prior phototherapy), or R5 (age > 336 h) triggered.
     */
    data class CannotScreen(
        val inputs: ScreeningInputs,
        val reasonCode: String,
        val triageResult: TriageResult,
        val versions: VersionsRecord
    ) : ScreeningState()

    /**
     * Card trust check: verifying batch signature against trust list.
     */
    data class CardCheck(
        val inputs: ScreeningInputs
    ) : ScreeningState()

    /**
     * Terminal state: Card verification failed (unverified signature, unknown issuer, or expired batch).
     * Screening blocked.
     */
    data class Blocked(
        val inputs: ScreeningInputs,
        val reason: CardBlockReason
    ) : ScreeningState()

    /**
     * Camera capture stage (attempt 1, 2, or 3).
     * If previous attempt failed, [previousFailure] holds the reason code for retake guidance.
     */
    data class Capture(
        val inputs: ScreeningInputs,
        val cardBatch: CardBatchPayload,
        val attempt: Int = 1,
        val previousFailure: QualityReasonCode? = null
    ) : ScreeningState()

    /**
     * Vision quality gate evaluation of captured frame.
     */
    data class Quality(
        val inputs: ScreeningInputs,
        val cardBatch: CardBatchPayload,
        val attempt: Int,
        val qualityResult: QualityGateResult
    ) : ScreeningState()

    /**
     * Terminal state: Capture quality gate failed 3 times.
     * Recommends blood test today (QUALITY_FALLBACK).
     */
    data class BloodTestFallback(
        val inputs: ScreeningInputs,
        val triageResult: TriageResult,
        val versions: VersionsRecord,
        val lastQualityResult: QualityGateResult? = null
    ) : ScreeningState()

    /**
     * Quality passed: computing color correction, feature extraction, estimation, and triage.
     */
    data class Analysis(
        val inputs: ScreeningInputs,
        val cardBatch: CardBatchPayload,
        val attempt: Int,
        val qualityResult: QualityGateResult
    ) : ScreeningState()

    /**
     * Result screen displaying triage action, reason, phototherapy threshold, and estimation interval.
     */
    data class Result(
        val inputs: ScreeningInputs,
        val triageResult: TriageResult,
        val versions: VersionsRecord,
        val intermediateData: ScreeningIntermediateData
    ) : ScreeningState()

    /**
     * Session record written to database/log.
     */
    data class Saved(
        val summary: ScreeningResultSummary
    ) : ScreeningState()
}
