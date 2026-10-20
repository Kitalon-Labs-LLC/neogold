package com.kitalonlabs.neogold.feature.research.data

/**
 * Format validation rules for research participant codes and consent form IDs
 * per doc 7 Section 7 and doc 5 Section 2.2.
 */
object ResearchFormats {

    /**
     * Participant code: 2-5 uppercase site prefix, hyphen, 3-6 digits (e.g. SKP-0142).
     * Rejects national IDs, phone numbers, and birth registration numbers (TC-RSH-09).
     */
    val PARTICIPANT_CODE_REGEX = Regex("^[A-Z]{2,5}-[0-9]{3,6}$")

    /**
     * Paper consent form ID: 2-5 uppercase prefix, hyphen, 2-26 uppercase alphanumeric/hyphen.
     * Required before any research capture (FR-26, TC-RSH-08).
     */
    val CONSENT_FORM_ID_REGEX = Regex("^[A-Z]{2,5}-[A-Z0-9-]{2,26}$")

    fun isValidParticipantCode(code: String): Boolean {
        return PARTICIPANT_CODE_REGEX.matches(code.trim())
    }

    fun isValidConsentFormId(formId: String): Boolean {
        return CONSENT_FORM_ID_REGEX.matches(formId.trim())
    }
}
