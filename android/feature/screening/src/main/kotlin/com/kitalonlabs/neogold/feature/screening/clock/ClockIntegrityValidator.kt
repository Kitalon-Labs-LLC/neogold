package com.kitalonlabs.neogold.feature.screening.clock

import android.content.ContentResolver
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clock integrity and birth time validation logic implementing doc 8 Section 14 (H2) and:
 * - TC-REL-03: Clock before build time blocks screening with clock_wrong_block
 * - TC-REL-04: Automatic date and time off warns with clock_auto_off
 * - TC-REL-05: Research clock drift > 10 min blocks screening
 * - TC-REL-06: Birth time in the future refused
 * - TC-UI-10: Display phone current time on S10
 */
object ClockIntegrityValidator {

    // Default release build timestamp baseline: 11 October 2026 00:00:00 UTC
    const val DEFAULT_BUILD_TIMESTAMP_EPOCH_MS = 1791676800000L

    // Maximum permitted drift from server time in research mode: 10 minutes
    const val MAX_RESEARCH_CLOCK_DRIFT_MS = 10 * 60 * 1000L

    sealed class ClockValidationResult {
        data object Valid : ClockValidationResult()
        data class Error(val messageResName: String) : ClockValidationResult()
    }

    /**
     * Checks whether the current phone clock is before the app build timestamp (TC-REL-03, H2).
     */
    fun isClockBeforeBuildTime(
        currentEpochMs: Long,
        buildTimestampEpochMs: Long = DEFAULT_BUILD_TIMESTAMP_EPOCH_MS
    ): Boolean {
        return currentEpochMs < buildTimestampEpochMs
    }

    /**
     * Checks if automatic date and time is enabled in Android settings (TC-REL-04, H2).
     */
    fun isAutoTimeEnabled(contentResolver: ContentResolver?): Boolean {
        if (contentResolver == null) return true
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 0) > 0
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Checks research mode clock drift against stored server offset (TC-REL-05, H2).
     * @param serverOffsetMs the recorded offset (server_time - phone_time)
     * @param currentDriftMs current skew measurement or discrepancy
     */
    fun isResearchClockDriftExceeded(
        currentDriftMs: Long,
        maxDriftMs: Long = MAX_RESEARCH_CLOCK_DRIFT_MS
    ): Boolean {
        return Math.abs(currentDriftMs) > maxDriftMs
    }

    /**
     * Checks if entered birth time is in the future relative to the phone's current time (TC-REL-06, H2).
     */
    fun isBirthTimeInFuture(
        birthTimeEpochMs: Long,
        currentEpochMs: Long
    ): Boolean {
        return birthTimeEpochMs > currentEpochMs
    }

    /**
     * Computes age in hours with floating-point precision (FR-05, doc 4 Section 8).
     */
    fun computeAgeInHours(
        birthTimeEpochMs: Long,
        currentEpochMs: Long
    ): Double {
        if (birthTimeEpochMs > currentEpochMs) return 0.0
        val diffMs = currentEpochMs - birthTimeEpochMs
        return diffMs / (1000.0 * 60.0 * 60.0)
    }

    /**
     * Formats the current phone time for S10 display (TC-UI-10, doc 6 Section 7.2).
     * Format e.g. "11 Oct 12:30 PM".
     */
    fun formatPhoneTimeNow(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.ENGLISH
    ): String {
        val instant = Instant.ofEpochMilli(epochMs)
        val formatter = DateTimeFormatter.ofPattern("d MMM h:mm a", locale)
            .withZone(zoneId)
        return formatter.format(instant)
    }
}
