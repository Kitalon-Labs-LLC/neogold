package com.kitalonlabs.neogold.feature.research.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kitalonlabs.neogold.feature.research.R

/** What a health worker can be told when unlocking research mode or saving a session. Each has an English and a Bangla text. */
enum class ResearchMessage {
    WRONG_PIN,
    LOCKED_OUT,
    VERIFIER_KEY_LOST,
    PINS_DO_NOT_MATCH,
    NO_STUDY,
    ANOTHER_STUDY,
    WEAK_PIN,
    QR_NOT_VALID,
    QR_EXPIRED,
    NO_CAPTURES,
    AGE_OUT_OF_RANGE,
    BAD_IMAGE,
    UNEXPECTED
}

/**
 * A message with the numbers it needs. The screen turns it into text in the language the app is showing; the view model
 * never builds user text, so nothing here is English-only.
 */
data class ResearchError(val message: ResearchMessage, val minutes: Int = 0, val attempts: Int = 0)

/** A refusal from the repository: the same message text as before for logs and tests, plus the typed reason for the screen. */
class ResearchRefusal(val error: ResearchError, message: String) : SecurityException(message)

@Composable
fun ResearchError.text(): String = when (message) {
    ResearchMessage.WRONG_PIN -> stringResource(R.string.r_err_wrong_pin, attempts)
    ResearchMessage.LOCKED_OUT -> stringResource(R.string.r_err_locked_out, minutes)
    ResearchMessage.VERIFIER_KEY_LOST -> stringResource(R.string.r_err_key_lost)
    ResearchMessage.PINS_DO_NOT_MATCH -> stringResource(R.string.r_err_pins_differ)
    ResearchMessage.NO_STUDY -> stringResource(R.string.r_err_no_study)
    ResearchMessage.ANOTHER_STUDY -> stringResource(R.string.r_err_another_study)
    ResearchMessage.WEAK_PIN -> stringResource(R.string.r_err_weak_pin)
    ResearchMessage.QR_NOT_VALID -> stringResource(R.string.r_err_qr_invalid)
    ResearchMessage.QR_EXPIRED -> stringResource(R.string.r_err_qr_expired)
    ResearchMessage.NO_CAPTURES -> stringResource(R.string.r_err_no_captures)
    ResearchMessage.AGE_OUT_OF_RANGE -> stringResource(R.string.r_err_age_range)
    ResearchMessage.BAD_IMAGE -> stringResource(R.string.r_err_bad_image)
    ResearchMessage.UNEXPECTED -> stringResource(R.string.r_err_unexpected)
}
