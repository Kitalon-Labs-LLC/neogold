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
     * Steps 1 and 2 of [evaluate] on their own, for the screen that decides whether the camera opens at all: the
     * configuration is available (enabled, signed for a public build, not expired) and none of the hard rules R1 to R5
     * fires. No estimate is needed, so none is read.
     *
     * @return [TriageResult.Unavailable] or the [TriageResult.Action] of the first hard rule that matches, or null when
     * capture may begin. This is the same code [evaluate] runs for those steps, so the two cannot disagree.
     */
    fun evaluateBeforeCapture(inputs: TriageInputs): TriageResult? = unavailableOrHardRule(inputs, interval = null)

    /**
     * Evaluates triage inputs against configuration rules and phototherapy thresholds.
     *
     * @param inputs Clinical screening inputs and bilirubin estimation interval.
     * @return [TriageResult.Action] or [TriageResult.Unavailable].
     */
    @Suppress("LongMethod", "ReturnCount") // ordered rules, the first match returns (doc 4 Section 6)
    fun evaluate(inputs: TriageInputs): TriageResult {
        val interval = if (inputs.estimate != null && inputs.lowerBound != null && inputs.upperBound != null) {
            PredictionInterval(
                estimate = inputs.estimate,
                lower = inputs.lowerBound,
                upper = inputs.upperBound
            )
        } else {
            null
        }

        // Steps 1 and 2: configuration availability, then the hard rules in strict order R1 to R5 (first match wins)
        unavailableOrHardRule(inputs, interval)?.let { return it }

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

    /** Step 1 (configuration availability) and step 2 (hard rules R1 to R5), shared by [evaluate] and [evaluateBeforeCapture]. */
    @Suppress("ReturnCount") // ordered rules, the first match returns (doc 4 Section 6)
    private fun unavailableOrHardRule(inputs: TriageInputs, interval: PredictionInterval?): TriageResult? {
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

        // Step 2: Hard rules in strict order R1 to R5 (first match wins)
        if (inputs.dangerSigns) {
            return hardRule(TriageAction.REFER_URGENT, TriageReasonCode.R1, interval)
        }
        if (inputs.jaundiceOnsetUnder24h) {
            return hardRule(TriageAction.REFER_URGENT, TriageReasonCode.R2, interval)
        }
        if (inputs.gestationalAgeWeeks < config.scope.minGestationalAgeWeeks) {
            return hardRule(TriageAction.CANNOT_SCREEN_BLOOD_TEST, TriageReasonCode.R3, interval)
        }
        if (inputs.priorPhototherapy) {
            return hardRule(TriageAction.CANNOT_SCREEN_BLOOD_TEST, TriageReasonCode.R4, interval)
        }
        if (inputs.ageHours > config.scope.maxAgeHours) {
            return hardRule(TriageAction.CANNOT_SCREEN_CLINICAL_REVIEW, TriageReasonCode.R5, interval)
        }
        return null
    }

    private fun hardRule(action: TriageAction, reasonCode: String, interval: PredictionInterval?) = TriageResult.Action(
        action = action,
        reasonCode = reasonCode,
        thresholdUsed = null,
        interval = interval,
        configVersion = config.configVersion
    )
}
