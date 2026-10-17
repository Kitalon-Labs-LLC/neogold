package com.kitalonlabs.neogold.engine.triage

import java.time.LocalDate

enum class SignoffStatus {
    DRAFT,
    SIGNED
}

data class ClinicalSignoff(
    val status: SignoffStatus,
    val signoffRecordSha256: String?
)

data class ScopeConfig(
    val minGestationalAgeWeeks: Int = 35,
    val maxAgeHours: Double = 336.0
)

data class HardRuleConfig(
    val id: String,
    val whenCondition: String,
    val action: TriageAction
)

data class BloodTestZoneConfig(
    val withinOfThreshold: Double,
    val absoluteAtOrAbove: Double
)

data class RecheckConfig(
    val withinOfThreshold: Double,
    val hours: Int
)

/**
 * Validated in-memory triage configuration.
 */
data class TriageConfig(
    val configVersion: String,
    val guideline: String,
    val units: String,
    val triageEnabled: Boolean,
    val validUntil: LocalDate,
    val clinicalSignoff: ClinicalSignoff,
    val scope: ScopeConfig,
    val hardRules: List<HardRuleConfig>,
    val phototherapyThresholds: Map<String, List<Pair<Double, Double>>>,
    val bloodTestZone: BloodTestZoneConfig,
    val recheck: RecheckConfig,
    val maxIntervalWidth: Double,
    val qualityThresholds: Map<String, Any?> = emptyMap(),
    val nonMonotonicBands: List<String> = emptyList()
) {
    /**
     * Maps a gestational age in completed weeks to the gestational age band ID.
     * Every GA from 35 to 44 maps to exactly one band:
     * - 35 -> "ga35"
     * - 36, 37 -> "ga36"
     * - 38..44 -> "ga38plus"
     */
    fun bandForGestationalAge(gaWeeks: Int): String {
        return when (gaWeeks) {
            35 -> "ga35"
            36, 37 -> "ga36"
            in 38..Int.MAX_VALUE -> "ga38plus"
            else -> throw IllegalArgumentException("Gestational age $gaWeeks is below minimum scope (35 weeks)")
        }
    }

    /**
     * Returns the threshold table points for the specified gestational age.
     */
    fun thresholdTableFor(gaWeeks: Int): List<Pair<Double, Double>> {
        val band = bandForGestationalAge(gaWeeks)
        return phototherapyThresholds[band]
            ?: throw IllegalStateException("Missing threshold table for band '$band'")
    }
}
