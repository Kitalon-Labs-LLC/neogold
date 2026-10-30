package com.kitalonlabs.neogold.feature.screening.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kitalonlabs.neogold.core.designsystem.R
import com.kitalonlabs.neogold.feature.screening.domain.CannotScreenReason
import com.kitalonlabs.neogold.feature.screening.domain.NextStep
import com.kitalonlabs.neogold.feature.screening.domain.ResultPresentation
import java.text.DateFormat
import java.util.Date

private const val MILLIS_PER_HOUR = 3_600_000L

/**
 * The line under the action's name, in the language the app is showing (doc 6 Section 3). For a recheck it names the time to
 * come back, with the date as well as the time so that a 24 hour recheck does not read as today: [shownAtMs] plus the
 * hours the engine gave.
 */
@Composable
fun ResultPresentation.nextStepText(shownAtMs: Long): String = when (nextStep) {
    NextStep.ARRANGE_TRANSPORT -> stringResource(R.string.rule_urgent_next_step)
    NextStep.SEND_BLOOD_TEST -> stringResource(R.string.next_blood_test)
    NextStep.CHECK_AGAIN -> {
        val at = shownAtMs + (recheckHours ?: 0) * MILLIS_PER_HOUR
        stringResource(
            R.string.next_recheck,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
        )
    }
    NextStep.CONTINUE_ROUTINE -> stringResource(R.string.next_no_further)
    NextStep.USE_BLOOD_TEST -> stringResource(R.string.next_use_blood_test)
    NextStep.NEEDS_DOCTOR_REVIEW -> stringResource(R.string.next_doctor_review)
}

/** Why NeoGold cannot screen this baby, in words; null for every other result. */
@Composable
fun ResultPresentation.reasonText(): String? = when (cannotScreenReason) {
    CannotScreenReason.NONE -> null
    CannotScreenReason.UNDER_35_WEEKS -> stringResource(R.string.rule_ga_reason)
    CannotScreenReason.PRIOR_PHOTOTHERAPY -> stringResource(R.string.rule_phototherapy_reason)
    CannotScreenReason.OVER_14_DAYS -> stringResource(R.string.rule_age_reason)
}
