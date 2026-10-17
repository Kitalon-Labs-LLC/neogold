package com.kitalonlabs.neogold.engine.triage

import java.time.LocalDate

/**
 * Pure Kotlin Triage Engine.
 *
 * Implements the decision algorithm specified in docs/4_System_Architecture.md Section 6.2.
 * Pure JVM engine with zero Android dependencies.
 */
class TriageEngine(
    val config: TriageConfig,
    val isPublicBuild: Boolean = false,
    val clock: () -> LocalDate = { LocalDate.now() }
) {

    /**
     * Evaluates triage inputs against configuration rules and phototherapy thresholds.
     *
     * @param inputs Clinical screening inputs and bilirubin estimation interval.
     * @return [TriageResult.Action] or [TriageResult.Unavailable].
     */
    fun evaluate(inputs: TriageInputs): TriageResult {
        // Step 1: Config availability check
        if (!config.triageEnabled) {
            return TriageResult.Unavailable(
                reason = TriageUnavailableReason.TRIAGE_DISABLED,
                message = "Triage is disabled in this configuration. Please confirm with a blood test.",
                configVersion = config.configVersion
            )
        }

        if (isPublicBuild && config.clinicalSignoff.status != SignoffStatus.SIGNED) {
            return TriageResult.Unavailable(
                reason = TriageUnavailableReason.CONFIG_UNSIGNED,
                message = "Clinical configuration is not signed for public release. Please confirm with a blood test.",
                configVersion = config.configVersion
            )
        }

        val today = clock()
        if (today.isAfter(config.validUntil)) {
            return TriageResult.Unavailable(
                reason = TriageUnavailableReason.CONFIG_EXPIRED,
                message = "Triage configuration has expired. Please update the application.",
                configVersion = config.configVersion
            )
        }

        val interval = if (inputs.estimate != null && inputs.lowerBound != null && inputs.upperBound != null) {
            PredictionInterval(
                estimate = inputs.estimate,
                lower = inputs.lowerBound,
                upper = inputs.upperBound
            )
        } else {
            null
        }

        // Step 2: Hard rules in strict order R1 to R5 (first match wins)
        if (inputs.dangerSigns) {
            return TriageResult.Action(
                action = TriageAction.REFER_URGENT,
                reasonCode = TriageReasonCode.R1,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (inputs.jaundiceOnsetUnder24h) {
            return TriageResult.Action(
                action = TriageAction.REFER_URGENT,
                reasonCode = TriageReasonCode.R2,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (inputs.gestationalAgeWeeks < config.scope.minGestationalAgeWeeks) {
            return TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_BLOOD_TEST,
                reasonCode = TriageReasonCode.R3,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (inputs.priorPhototherapy) {
            return TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_BLOOD_TEST,
                reasonCode = TriageReasonCode.R4,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (inputs.ageHours > config.scope.maxAgeHours) {
            return TriageResult.Action(
                action = TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW,
                reasonCode = TriageReasonCode.R5,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        // Step 3: Quality fallback (3 failed capture attempts)
        if (inputs.qualityFailedAttempts >= 3) {
            return TriageResult.Action(
                action = TriageAction.BLOOD_TEST_TODAY,
                reasonCode = TriageReasonCode.QUALITY_FALLBACK,
                thresholdUsed = null,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        // Step 4 & 5: Threshold lookup and prediction interval
        val table = config.thresholdTableFor(inputs.gestationalAgeWeeks)
        val tTenths = TriageArithmetic.interpolateThresholdTenths(table, inputs.ageHours)
        val thresholdUsed = TriageArithmetic.tenthsToDouble(tTenths)

        val estimate = inputs.estimate
            ?: throw IllegalArgumentException("Bilirubin estimate (E) is required when hard rules and quality pass")
        val lower = inputs.lowerBound
            ?: throw IllegalArgumentException("Lower bound (L) is required when hard rules and quality pass")
        val upper = inputs.upperBound
            ?: throw IllegalArgumentException("Upper bound (U) is required when hard rules and quality pass")

        // Step 8: Integer-tenth arithmetic with rounding toward more care
        val eTenths = TriageArithmetic.toTenthsCeiling(estimate)
        val lTenths = TriageArithmetic.toTenthsFloor(lower)
        val uTenths = TriageArithmetic.toTenthsCeiling(upper)

        val bloodWithinTenths = TriageArithmetic.toTenthsHalfUp(config.bloodTestZone.withinOfThreshold)
        val bloodAbsTenths = TriageArithmetic.toTenthsHalfUp(config.bloodTestZone.absoluteAtOrAbove)
        val maxWidthTenths = TriageArithmetic.toTenthsHalfUp(config.maxIntervalWidth)
        val recheckWithinTenths = TriageArithmetic.toTenthsHalfUp(config.recheck.withinOfThreshold)

        // Decision rules: evaluated in strict order (doc 4 Section 6.2)
        // 1. REFER_URGENT
        if (eTenths >= tTenths) {
            return TriageResult.Action(
                action = TriageAction.REFER_URGENT,
                reasonCode = TriageReasonCode.ABOVE_THRESHOLD,
                thresholdUsed = thresholdUsed,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (uTenths >= tTenths - bloodWithinTenths) {
            return TriageResult.Action(
                action = TriageAction.BLOOD_TEST_TODAY,
                reasonCode = TriageReasonCode.ZONE_WITHIN3,
                thresholdUsed = thresholdUsed,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (uTenths >= bloodAbsTenths) {
            return TriageResult.Action(
                action = TriageAction.BLOOD_TEST_TODAY,
                reasonCode = TriageReasonCode.ZONE_ABS15,
                thresholdUsed = thresholdUsed,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (uTenths - lTenths > maxWidthTenths) {
            return TriageResult.Action(
                action = TriageAction.BLOOD_TEST_TODAY,
                reasonCode = TriageReasonCode.WIDE_INTERVAL,
                thresholdUsed = thresholdUsed,
                interval = interval,
                configVersion = config.configVersion
            )
        }

        if (uTenths >= tTenths - recheckWithinTenths) {
            return TriageResult.Action(
                action = TriageAction.RECHECK,
                reasonCode = TriageReasonCode.RECHECK_ZONE,
                thresholdUsed = thresholdUsed,
                interval = interval,
                configVersion = config.configVersion,
                recheckHours = config.recheck.hours
            )
        }

        return TriageResult.Action(
            action = TriageAction.NO_FURTHER_ACTION_FROM_SCREEN,
            reasonCode = TriageReasonCode.NONE,
            thresholdUsed = thresholdUsed,
            interval = interval,
            configVersion = config.configVersion
        )
    }
}
