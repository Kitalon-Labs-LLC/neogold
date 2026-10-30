package com.kitalonlabs.neogold.feature.screening.domain

import com.kitalonlabs.neogold.core.designsystem.components.organisms.TriageUiAction
import com.kitalonlabs.neogold.engine.triage.PredictionInterval
import com.kitalonlabs.neogold.engine.triage.TriageAction
import com.kitalonlabs.neogold.engine.triage.TriageReasonCode
import com.kitalonlabs.neogold.engine.triage.TriageResult

/** The one line under the action's name (doc 6 Section 3). The screen turns each into text in the language it shows. */
enum class NextStep {
    /** "Arrange transport to the hospital now." */
    ARRANGE_TRANSPORT,

    /** "Send for a blood test today." */
    SEND_BLOOD_TEST,

    /** "Check again at [time]. Come back sooner if danger signs appear." */
    CHECK_AGAIN,

    /** "Continue routine care. Watch for danger signs." */
    CONTINUE_ROUTINE,

    /** "Use a blood test" (a "cannot screen" card). */
    USE_BLOOD_TEST,

    /** "This baby needs a doctor's review" (a "cannot screen" card). */
    NEEDS_DOCTOR_REVIEW
}

/** Why NeoGold cannot screen this baby, in words (doc 6 Section 3); none for the other results. */
enum class CannotScreenReason { NONE, UNDER_35_WEEKS, PRIOR_PHOTOTHERAPY, OVER_14_DAYS }

/**
 * Everything a result screen and a saved record need from a [TriageResult], decided in one place so the card the worker
 * sees and the record that is saved cannot differ (BUG-2026-021). It holds no text: the screen writes it.
 *
 * @property storedAction the value written to `screening_session.triage_action` (doc 5 Section 2.2)
 * @property interval the estimate and its interval, only when triage really produced one; null for a hard rule, a fallback
 * and an unavailable triage, so a result that has no estimate never shows or stores one
 */
data class ResultPresentation(
    val uiAction: TriageUiAction,
    val nextStep: NextStep,
    val cannotScreenReason: CannotScreenReason,
    val recheckHours: Int?,
    val storedAction: String,
    val reasonCode: String?,
    val interval: PredictionInterval?,
    val thresholdMgDl: Double?
)

/** The action a saved record carries when triage was not available: the worker was told to use a blood test. */
private val UNAVAILABLE_STORED_ACTION = TriageAction.CANNOT_SCREEN_BLOOD_TEST.name

/**
 * Turns the engine's answer into what is shown and stored. An answer the app cannot present exactly (a recheck with no
 * hours) is presented as the more careful action, a blood test, and never as something milder.
 */
fun TriageResult.toPresentation(): ResultPresentation = when (this) {
    is TriageResult.Unavailable -> ResultPresentation(
        uiAction = TriageUiAction.CANNOT_SCREEN,
        nextStep = NextStep.USE_BLOOD_TEST,
        cannotScreenReason = CannotScreenReason.NONE,
        recheckHours = null,
        storedAction = UNAVAILABLE_STORED_ACTION,
        reasonCode = null,
        interval = null,
        thresholdMgDl = null
    )
    is TriageResult.Action -> presentAction()
}

@Suppress("CyclomaticComplexMethod") // one branch per engine action: the mapping is the specification (doc 6 Section 3)
private fun TriageResult.Action.presentAction(): ResultPresentation {
    fun present(
        ui: TriageUiAction,
        next: NextStep,
        hours: Int? = null,
        why: CannotScreenReason = CannotScreenReason.NONE
    ) =
        ResultPresentation(
            uiAction = ui,
            nextStep = next,
            cannotScreenReason = why,
            recheckHours = hours,
            storedAction = action.name,
            reasonCode = reasonCode.takeUnless { it == TriageReasonCode.NONE },
            interval = interval,
            thresholdMgDl = thresholdUsed
        )
    val hours = recheckHours // a property of another module cannot be smart-cast
    return when (action) {
        TriageAction.REFER_URGENT -> present(TriageUiAction.REFER_URGENT, NextStep.ARRANGE_TRANSPORT)
        TriageAction.BLOOD_TEST_TODAY -> present(TriageUiAction.BLOOD_TEST, NextStep.SEND_BLOOD_TEST)
        TriageAction.RECHECK ->
            if (hours != null && hours > 0) {
                present(TriageUiAction.RECHECK, NextStep.CHECK_AGAIN, hours = hours)
            } else {
                // a recheck that does not say when cannot be shown; a blood test is the careful choice
                present(
                    TriageUiAction.BLOOD_TEST,
                    NextStep.SEND_BLOOD_TEST
                ).copy(storedAction = TriageAction.BLOOD_TEST_TODAY.name)
            }
        TriageAction.NO_FURTHER_ACTION_FROM_SCREEN -> present(
            TriageUiAction.NO_FURTHER_ACTION,
            NextStep.CONTINUE_ROUTINE
        )
        TriageAction.CANNOT_SCREEN_BLOOD_TEST -> present(
            TriageUiAction.CANNOT_SCREEN,
            NextStep.USE_BLOOD_TEST,
            why = when (reasonCode) {
                TriageReasonCode.R3 -> CannotScreenReason.UNDER_35_WEEKS
                TriageReasonCode.R4 -> CannotScreenReason.PRIOR_PHOTOTHERAPY
                else -> CannotScreenReason.NONE
            }
        )
        TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW -> present(
            TriageUiAction.CANNOT_SCREEN,
            NextStep.NEEDS_DOCTOR_REVIEW,
            why = CannotScreenReason.OVER_14_DAYS
        )
    }
}
