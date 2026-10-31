package com.kitalonlabs.neogold.sync.research

import com.kitalonlabs.neogold.sync.research.api.ApiException
import com.kitalonlabs.neogold.sync.research.api.models.ErrorCode
import com.kitalonlabs.neogold.sync.research.enrollment.EnrollmentRefusal
import com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentRefused
import com.kitalonlabs.neogold.sync.research.enrollment.StudySettingsMismatchException
import java.io.IOException

/**
 * Why a join or leave did not work, as a plain kind with no text. The app turns a kind into a message in the language it
 * shows; no server answer, host, code or exception message leaves this module (doc 8 Section 6.6). It exists so `:app` need
 * not see the HTTP client's types.
 */
enum class ServerFailureKind {
    /** The one-time code was wrong, already used or out of date, or the phone already has a key on the server. */
    CODE_REJECTED,

    /** The server's policy is not the one the signed QR promised, or its photo limit is below the app's. */
    SETTINGS_MISMATCH,

    /** No answer: no network, a timeout, a certificate that did not match. */
    UNREACHABLE,

    /** The server answered and refused (a closed study, an old app, a rule), or the QR cannot be used. */
    REFUSED,

    /** No study on the phone yet, or the QR is of another study. */
    NO_STUDY,
    OTHER_STUDY,

    /** The study has no server. */
    NO_SERVER,

    /** Anything else. */
    UNEXPECTED
}

/** Sorts a failure of [StudyEnrollmentManager][com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentManager] or leaving. */
fun classifyServerFailure(failure: Throwable): ServerFailureKind = when (failure) {
    is StudyEnrollmentRefused -> when (failure.reason) {
        EnrollmentRefusal.NO_STUDY -> ServerFailureKind.NO_STUDY
        EnrollmentRefusal.OTHER_STUDY -> ServerFailureKind.OTHER_STUDY
        EnrollmentRefusal.NO_SERVER -> ServerFailureKind.NO_SERVER
        EnrollmentRefusal.ALREADY_ENROLLED -> ServerFailureKind.UNEXPECTED
    }
    is StudySettingsMismatchException -> ServerFailureKind.SETTINGS_MISMATCH
    is ApiException -> when (failure.errorCode) {
        ErrorCode.ENROLLMENT_CODE_INVALID,
        ErrorCode.ENROLLMENT_CODE_EXPIRED,
        ErrorCode.ENROLLMENT_CODE_USED,
        ErrorCode.DEVICE_KEY_ALREADY_ENROLLED -> ServerFailureKind.CODE_REJECTED
        else -> ServerFailureKind.REFUSED
    }
    is IOException -> ServerFailureKind.UNREACHABLE
    // the signed profile did not verify, or names an address the app will not send data to (https only, no path)
    is IllegalArgumentException -> ServerFailureKind.REFUSED
    else -> ServerFailureKind.UNEXPECTED
}
