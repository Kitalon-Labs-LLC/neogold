package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.security.integrity.IntegrityChecker
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageEngine
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure Kotlin Screening State Machine implementing docs/4_System_Architecture.md Section 4.4.
 *
 * State flow:
 * [*] -> Inputs
 * Inputs -> RuleCheck: inputs valid (consent confirmed)
 * RuleCheck -> UrgentReferral: danger signs (R1) or jaundice under 24 h (R2)
 * RuleCheck -> CannotScreen: GA under 35 wk (R3), prior phototherapy (R4), age over 14 d (R5)
 * RuleCheck -> CardCheck: no hard rule fired
 * CardCheck -> Blocked: card unverified or expired
 * CardCheck -> Capture: card batch verified
 * Capture -> Quality: frame evaluated
 * Quality -> Capture: failed, attempts under 3
 * Quality -> BloodTestFallback: failed 3 times
 * Quality -> Analysis: passed
 * Analysis -> Result: estimate & triage computed
 * Result -> Saved: session logged
 * UrgentReferral -> Saved
 * CannotScreen -> Saved
 * BloodTestFallback -> Saved
 * Blocked -> [*]
 * Saved -> [*]
 *
 * The UI only renders state; it never decides clinical flow.
 */
class ScreeningStateMachine(
    private val integrityChecker: IntegrityChecker,
    initialState: ScreeningState = ScreeningState.Initial
) {
    private val _state = MutableStateFlow<ScreeningState>(initialState)
    val state: StateFlow<ScreeningState> = _state.asStateFlow()

    val currentState: ScreeningState
        get() = _state.value

    /**
     * Dispatches an event to advance the state machine.
     * Returns the new state.
     */
    fun dispatch(event: ScreeningEvent): ScreeningState {
        val nextState = when (event) {
            is ScreeningEvent.StartScreening -> handleStartScreening()
            is ScreeningEvent.SubmitInputs -> handleSubmitInputs(event.inputs)
            is ScreeningEvent.EvaluateRules -> handleEvaluateRules(event.triageEngine, event.versions)
            is ScreeningEvent.CardVerified -> handleCardVerified(event.verificationResult)
            is ScreeningEvent.FrameCaptured -> handleFrameCaptured(event)
            is ScreeningEvent.AnalysisFinished -> handleAnalysisFinished(event.intermediateData)
            is ScreeningEvent.SaveSession -> handleSaveSession(event.sessionId)
            is ScreeningEvent.Reset -> handleReset()
        }
        _state.value = nextState
        return nextState
    }

    private fun handleStartScreening(): ScreeningState {
        if (!integrityChecker.isScreeningAllowed()) {
            return ScreeningState.Blocked(
                inputs = ScreeningInputs(
                    gestationalAgeWeeks = 38,
                    ageHours = 48.0,
                    dangerSigns = false,
                    jaundiceOnsetUnder24h = false,
                    priorPhototherapy = false,
                    guardianConsentConfirmed = false
                ),
                reason = CardBlockReason.ASSET_INTEGRITY_COMPROMISED
            )
        }
        return ScreeningState.Inputs()
    }

    private fun handleSubmitInputs(inputs: ScreeningInputs): ScreeningState {
        if (!integrityChecker.isScreeningAllowed()) {
            return ScreeningState.Blocked(inputs, CardBlockReason.ASSET_INTEGRITY_COMPROMISED)
        }
        // Guardian consent is mandatory before opening the camera (doc 2 Section 11, doc 10 TC-UI-13)
        if (!inputs.guardianConsentConfirmed) {
            // Stay in inputs state with unconfirmed consent
            return ScreeningState.Inputs(inputs)
        }
        return ScreeningState.RuleCheck(inputs)
    }

    @Suppress("ReturnCount") // reworked with the screening wiring in E1
    private fun handleEvaluateRules(triageEngine: TriageEngine, versions: VersionsRecord): ScreeningState {
        val current = _state.value
        val inputs = when (current) {
            is ScreeningState.RuleCheck -> current.inputs
            is ScreeningState.Inputs -> current.inputs ?: return current
            else -> return current
        }

        if (!integrityChecker.isScreeningAllowed()) {
            return ScreeningState.Blocked(inputs, CardBlockReason.ASSET_INTEGRITY_COMPROMISED)
        }

        // Evaluate hard rules R1 to R5 (first match wins, doc 4 Section 6.2)
        if (inputs.dangerSigns) {
            val result = TriageResult.Action(
                action = TriageAction.REFER_URGENT,
                reasonCode = TriageReasonCode.R1,
                thresholdUsed = null,
                interval = null,
                configVersion = triageEngine.config.configVersion
            )
            return ScreeningState.UrgentReferral(inputs, TriageReasonCode.R1, result, versions)
        }

        if (inputs.jaundiceOnsetUnder24h) {
            val result = TriageResult.Action(
                action = TriageAction.REFER_URGENT,
                reasonCode = TriageReasonCode.R2,
                thresholdUsed = null,
                interval = null,
                configVersion = triageEngine.config.configVersion
            )
            return ScreeningState.UrgentReferral(inputs, TriageReasonCode.R2, result, versions)
        }

        if (inputs.gestationalAgeWeeks < triageEngine.config.scope.minGestationalAgeWeeks) {
            val result = TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_BLOOD_TEST,
                reasonCode = TriageReasonCode.R3,
                thresholdUsed = null,
                interval = null,
                configVersion = triageEngine.config.configVersion
            )
            return ScreeningState.CannotScreen(inputs, TriageReasonCode.R3, result, versions)
        }

        if (inputs.priorPhototherapy) {
            val result = TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_BLOOD_TEST,
                reasonCode = TriageReasonCode.R4,
                thresholdUsed = null,
                interval = null,
                configVersion = triageEngine.config.configVersion
            )
            return ScreeningState.CannotScreen(inputs, TriageReasonCode.R4, result, versions)
        }

        if (inputs.ageHours > triageEngine.config.scope.maxAgeHours) {
            val result = TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW,
                reasonCode = TriageReasonCode.R5,
                thresholdUsed = null,
                interval = null,
                configVersion = triageEngine.config.configVersion
            )
            return ScreeningState.CannotScreen(inputs, TriageReasonCode.R5, result, versions)
        }

        // No hard rule fired -> proceed to card check
        return ScreeningState.CardCheck(inputs)
    }

    private fun handleCardVerified(
        verificationResult: VerificationResult<com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload>
    ): ScreeningState {
        val current = _state.value
        val inputs = when (current) {
            is ScreeningState.CardCheck -> current.inputs
            is ScreeningState.RuleCheck -> current.inputs
            else -> return current
        }

        return when (verificationResult) {
            is VerificationResult.Verified -> {
                ScreeningState.Capture(
                    inputs = inputs,
                    cardBatch = verificationResult.payload,
                    attempt = 1,
                    previousFailure = null
                )
            }
            is VerificationResult.BadSignature -> {
                ScreeningState.Blocked(inputs, CardBlockReason.UNVERIFIED_SIGNATURE)
            }
            is VerificationResult.UnknownIssuer -> {
                ScreeningState.Blocked(inputs, CardBlockReason.UNKNOWN_ISSUER)
            }
            is VerificationResult.Expired -> {
                ScreeningState.Blocked(inputs, CardBlockReason.BATCH_EXPIRED)
            }
            is VerificationResult.Revoked -> {
                ScreeningState.Blocked(inputs, CardBlockReason.BATCH_REVOKED)
            }
            is VerificationResult.WrongPurpose -> {
                ScreeningState.Blocked(inputs, CardBlockReason.WRONG_PURPOSE)
            }
            is VerificationResult.Malformed -> {
                ScreeningState.Blocked(inputs, CardBlockReason.MALFORMED_PAYLOAD)
            }
        }
    }

    @Suppress("ReturnCount") // reworked with the screening wiring in E1
    private fun handleFrameCaptured(event: ScreeningEvent.FrameCaptured): ScreeningState {
        val current = _state.value
        val (inputs, cardBatch, attempt) = when (current) {
            is ScreeningState.Capture -> Triple(current.inputs, current.cardBatch, current.attempt)
            is ScreeningState.Quality -> Triple(current.inputs, current.cardBatch, current.attempt)
            else -> return current
        }

        val quality = event.qualityResult
        if (quality.passed) {
            return ScreeningState.Analysis(
                inputs = inputs,
                cardBatch = cardBatch,
                attempt = attempt,
                qualityResult = quality
            )
        }

        // Quality check failed
        val primaryFailure = quality.failureReasons.firstOrNull()
        if (attempt < 3) {
            // Loop back to capture with attempt incremented
            return ScreeningState.Capture(
                inputs = inputs,
                cardBatch = cardBatch,
                attempt = attempt + 1,
                previousFailure = primaryFailure
            )
        }

        // 3 failed attempts: Blood test fallback (QUALITY_FALLBACK, doc 4 Section 6.2)
        val fallbackVersions = event.fallbackVersions ?: VersionsRecord(
            appVersion = "1.0.0",
            modelVersion = "unknown",
            configVersion = "unknown",
            cardBatchId = cardBatch.batchId,
            deviceModel = "unknown",
            deviceManufacturer = "unknown",
            osVersion = "unknown"
        )

        val fallbackResult = TriageResult.Action(
            action = TriageAction.BLOOD_TEST_TODAY,
            reasonCode = TriageReasonCode.QUALITY_FALLBACK,
            thresholdUsed = null,
            interval = null,
            configVersion = fallbackVersions.configVersion
        )

        return ScreeningState.BloodTestFallback(
            inputs = inputs,
            triageResult = fallbackResult,
            versions = fallbackVersions,
            lastQualityResult = quality
        )
    }

    private fun handleAnalysisFinished(intermediateData: ScreeningIntermediateData): ScreeningState {
        val current = _state.value
        val inputs = when (current) {
            is ScreeningState.Analysis -> current.inputs
            is ScreeningState.Quality -> current.inputs
            is ScreeningState.Capture -> current.inputs
            else -> return current
        }

        return ScreeningState.Result(
            inputs = inputs,
            triageResult = intermediateData.triageResult,
            versions = intermediateData.versions,
            intermediateData = intermediateData
        )
    }

    private fun handleSaveSession(sessionId: String): ScreeningState {
        val current = _state.value
        val summary = when (current) {
            is ScreeningState.Result -> ScreeningResultSummary(
                sessionId = sessionId,
                inputs = current.inputs,
                triageResult = current.triageResult,
                versions = current.versions
            )
            is ScreeningState.UrgentReferral -> ScreeningResultSummary(
                sessionId = sessionId,
                inputs = current.inputs,
                triageResult = current.triageResult,
                versions = current.versions
            )
            is ScreeningState.CannotScreen -> ScreeningResultSummary(
                sessionId = sessionId,
                inputs = current.inputs,
                triageResult = current.triageResult,
                versions = current.versions
            )
            is ScreeningState.BloodTestFallback -> ScreeningResultSummary(
                sessionId = sessionId,
                inputs = current.inputs,
                triageResult = current.triageResult,
                versions = current.versions
            )
            else -> return current
        }

        return ScreeningState.Saved(summary)
    }

    private fun handleReset(): ScreeningState {
        return ScreeningState.Initial
    }
}
