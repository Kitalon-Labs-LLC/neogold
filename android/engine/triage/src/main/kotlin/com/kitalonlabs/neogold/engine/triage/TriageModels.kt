package com.kitalonlabs.neogold.engine.triage

/**
 * The triage actions produced by the triage engine (docs/4_System_Architecture.md Section 6.2).
 */
enum class TriageAction {
    REFER_URGENT,
    BLOOD_TEST_TODAY,
    RECHECK,
    NO_FURTHER_ACTION_FROM_SCREEN,
    CANNOT_SCREEN_BLOOD_TEST,
    CANNOT_SCREEN_CLINICAL_REVIEW
}

/**
 * Standard reason codes (the first rule that matched in strict order).
 */
object TriageReasonCode {
    const val R1 = "R1"
    const val R2 = "R2"
    const val R3 = "R3"
    const val R4 = "R4"
    const val R5 = "R5"
    const val QUALITY_FALLBACK = "QUALITY_FALLBACK"
    const val ABOVE_THRESHOLD = "ABOVE_THRESHOLD"
    const val ZONE_WITHIN3 = "ZONE_WITHIN3"
    const val ZONE_ABS15 = "ZONE_ABS15"
    const val WIDE_INTERVAL = "WIDE_INTERVAL"
    const val RECHECK_ZONE = "RECHECK_ZONE"
    const val NONE = "-"
}

/**
 * Bilirubin estimate and prediction interval (in mg/dL).
 */
data class PredictionInterval(
    val estimate: Double,
    val lower: Double,
    val upper: Double
)

/**
 * Reason why triage decision could not be generated.
 */
enum class TriageUnavailableReason {
    TRIAGE_DISABLED,
    CONFIG_EXPIRED,
    CONFIG_UNSIGNED,
    CONFIG_INVALID
}

/**
 * Typed triage outcome.
 */
sealed interface TriageResult {
    /**
     * A valid clinical decision-support triage action.
     */
    data class Action(
        val action: TriageAction,
        val reasonCode: String,
        val thresholdUsed: Double?,
        val interval: PredictionInterval?,
        val configVersion: String,
        val recheckHours: Int? = null
    ) : TriageResult

    /**
     * Triage is unavailable (disabled, unsigned in public build, expired, or invalid).
     */
    data class Unavailable(
        val reason: TriageUnavailableReason,
        val message: String,
        val configVersion: String? = null
    ) : TriageResult
}

/**
 * Clinical inputs for the triage engine.
 */
data class TriageInputs(
    val dangerSigns: Boolean = false,
    val jaundiceOnsetUnder24h: Boolean = false,
    val gestationalAgeWeeks: Int,
    val priorPhototherapy: Boolean = false,
    val ageHours: Double,
    val qualityFailedAttempts: Int = 0,
    val estimate: Double? = null,
    val lowerBound: Double? = null,
    val upperBound: Double? = null
)
