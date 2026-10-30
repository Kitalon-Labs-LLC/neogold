package com.kitalonlabs.neogold.feature.research.data

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
