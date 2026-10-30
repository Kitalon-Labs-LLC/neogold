package com.kitalonlabs.neogold.feature.research.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kitalonlabs.neogold.feature.research.R
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchMessage

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
    ResearchMessage.ENROLL_CODE_FORMAT -> stringResource(R.string.r_err_code_format)
    ResearchMessage.ENROLL_CODE_REJECTED -> stringResource(R.string.r_err_code_rejected)
    ResearchMessage.ENROLL_OTHER_STUDY -> stringResource(R.string.r_err_other_study)
    ResearchMessage.ENROLL_SETTINGS_MISMATCH -> stringResource(R.string.r_err_settings_mismatch)
    ResearchMessage.SERVER_UNREACHABLE -> stringResource(R.string.r_err_unreachable)
    ResearchMessage.SERVER_REFUSED -> stringResource(R.string.r_err_refused)
    ResearchMessage.NOT_ENROLLED -> stringResource(R.string.r_err_not_enrolled)
    ResearchMessage.UNEXPECTED -> stringResource(R.string.r_err_unexpected)
}
