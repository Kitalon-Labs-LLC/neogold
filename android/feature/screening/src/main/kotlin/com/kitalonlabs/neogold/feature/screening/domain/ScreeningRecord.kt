package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult

/** `VersionsRecord.cardBatchId` before a card was scanned in this session (a hard rule decided first). Stored as null. */
const val NO_CARD_SCANNED = ""

private const val MILLIS_PER_HOUR = 3_600_000L

/**
 * The row for the screening log, built from the state a session ended in. Everything in it comes from what the engine
 * decided and what the worker entered: the action is the engine's own action name, the reason is the engine's reason code,
 * the flags are the worker's answers, and the versions and the card batch are the ones loaded and scanned (BUG-2026-021,
 * 022; doc 5 Section 2.2). An estimate is stored only when the engine produced one with its decision; a rule outcome, a
 * fallback or an unavailable triage stores none.
 *
 * @param registerNumber the facility register number the worker typed, or null
 * @param deviceProfileId the phone's active device profile, or null if it has none
 * @param captureAttempts how many photos were taken
 * @return null for a state that is not the end of a session with a decision (a blocked start has none to record)
 */
fun ScreeningState.toSessionEntity(
    sessionId: String,
    nowMs: Long,
    registerNumber: String?,
    deviceProfileId: String?,
    captureAttempts: Int
): ScreeningSessionEntity? {
    val ended = endedSession() ?: return null
    val shown = ended.triageResult.toPresentation()
    val reason = shown.reasonCode
    val estimate = shown.interval
    return ScreeningSessionEntity(
        id = sessionId,
        mode = "SCREENING",
        status = "COMPLETED",
        createdAt = nowMs,
        completedAt = nowMs,
        ageHours = ended.inputs.ageHours,
        guardianConsentConfirmed = if (ended.inputs.guardianConsentConfirmed) 1 else 0,
        gestationalAgeWeeks = ended.inputs.gestationalAgeWeeks,
        flagJaundiceUnder24h = ended.inputs.jaundiceOnsetUnder24h.toInt(),
        flagDangerSigns = ended.inputs.dangerSigns.toInt(),
        flagPriorPhototherapy = ended.inputs.priorPhototherapy.toInt(),
        ruleOutcome = reason?.takeIf { it in HARD_RULES },
        triageAction = shown.storedAction,
        reasonCodes = reason?.let { "[\"$it\"]" },
        estimateMgDl = estimate?.estimate,
        estimateLowMgDl = estimate?.lower,
        estimateHighMgDl = estimate?.upper,
        thresholdMgDl = shown.thresholdMgDl,
        recheckDueAt = shown.recheckHours?.let { nowMs + it * MILLIS_PER_HOUR },
        captureAttempts = captureAttempts,
        registerNumber = registerNumber,
        registerNumberErasedAt = null,
        participantId = null,
        estimateHidden = 0,
        experimentalLabelShown = 1,
        deviceProfileId = deviceProfileId,
        cardBatchId = ended.versions.cardBatchId.takeIf { it != NO_CARD_SCANNED },
        appVersion = ended.versions.appVersion,
        // a model version is recorded only when a model ran
        modelVersion = ended.versions.modelVersion.takeIf { this is ScreeningState.Result },
        configVersion = ended.versions.configVersion
    )
}

private val HARD_RULES = setOf(
    TriageReasonCode.R1,
    TriageReasonCode.R2,
    TriageReasonCode.R3,
    TriageReasonCode.R4,
    TriageReasonCode.R5
)

private fun Boolean.toInt() = if (this) 1 else 0

private class EndedSession(
    val inputs: ScreeningInputs,
    val triageResult: TriageResult,
    val versions: VersionsRecord
)

private fun ScreeningState.endedSession(): EndedSession? = when (this) {
    is ScreeningState.UrgentReferral -> EndedSession(inputs, triageResult, versions)
    is ScreeningState.CannotScreen -> EndedSession(inputs, triageResult, versions)
    is ScreeningState.BloodTestFallback -> EndedSession(inputs, triageResult, versions)
    is ScreeningState.TriageUnavailable -> EndedSession(inputs, triageResult, versions)
    is ScreeningState.Result -> EndedSession(inputs, triageResult, versions)
    // a blocked or unfinished session has no decision to record
    else -> null
}
