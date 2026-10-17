package com.kitalonlabs.neogold.navigation

/**
 * Screen inventory defined in doc 6 Section 5.
 * Covers screening screens S01 to S25 (including S10a) and research screens R01 to R09.
 */
object Destinations {
    // Onboarding & Device Setup (S01 to S08)
    const val S01_LANGUAGE = "s01_language"
    const val S02_S04_SAFETY_NOTICE = "s02_s04_safety_notice"
    const val S05_DEVICE_CHECK = "s05_device_check"
    const val S06_CARD_SCAN = "s06_card_scan"
    const val S07_QUALIFICATION = "s07_qualification"
    const val S08_QUALIFICATION_RESULT = "s08_qualification_result"

    // Home (S09)
    const val S09_HOME = "s09_home"

    // Screening Flow (S10a to S20)
    const val S10A_GUARDIAN_CONSENT = "s10a_guardian_consent"
    const val S10_BIRTH_TIME_GA = "s10_birth_time_ga"
    const val S11_EARLY_JAUNDICE = "s11_early_jaundice"
    const val S12_DANGER_SIGNS = "s12_danger_signs"
    const val S13_PHOTOTHERAPY = "s13_phototherapy"
    const val S14_RULE_OUTCOME = "s14_rule_outcome"
    const val S15_CAPTURE_GUIDE = "s15_capture_guide"
    const val S16_CAMERA = "s16_camera"
    const val S17_PHOTO_PROBLEM = "s17_photo_problem"
    const val S18_FOREHEAD_PHOTO = "s18_forehead_photo"
    const val S19_ANALYZING = "s19_analyzing"
    const val S20_RESULT = "s20_result"

    // Screening Log & Settings (S21 to S25)
    const val S21_LOG = "s21_log"
    const val S22_SESSION_DETAIL = "s22_session_detail"
    const val S23_SETTINGS = "s23_settings"
    const val S24_ABOUT = "s24_about"
    const val S25_DELETE_DATA = "s25_delete_data"

    // Research Mode (R01 to R09)
    const val R01_RESEARCH_UNLOCK = "r01_research_unlock"
    const val R02_STUDY_HOME = "r02_study_home"
    const val R03_PARTICIPANT_CONSENT = "r03_participant_consent"
    const val R04_CAPTURE_PROTOCOL = "r04_capture_protocol"
    const val R05_TSB_ENTRY = "r05_tsb_entry"
    const val R06_SESSION_COMPLETE = "r06_session_complete"
    const val R07_EXPORT_SYNC = "r07_export_sync"
    const val R08_ENROLLMENT = "r08_enrollment"
    const val R09_LEAVE_STUDY = "r09_leave_study"
}
