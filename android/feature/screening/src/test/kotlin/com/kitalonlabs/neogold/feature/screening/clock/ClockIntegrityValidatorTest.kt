package com.kitalonlabs.neogold.feature.screening.clock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Unit tests verifying clock integrity checks:
 * - TC-REL-03: Clock before build time
 * - TC-REL-05: Research clock drift
 * - TC-REL-06: Birth time in the future
 * - TC-UI-10: Phone time now readout
 */
class ClockIntegrityValidatorTest {

    private val buildTimeMs = 1791676800000L // 11 Oct 2026 00:00:00 UTC

    /**
     * TC-REL-03: Set the phone date before the app's build date.
     * Expected: Screening blocked with clock_wrong_block.
     */
    @Test
    fun testClockBeforeBuildTimeBlocked() {
        val clockBefore = buildTimeMs - (24 * 60 * 60 * 1000L) // 1 day before
        val isBefore = ClockIntegrityValidator.isClockBeforeBuildTime(
            currentEpochMs = clockBefore,
            buildTimestampEpochMs = buildTimeMs
        )
        assertThat(isBefore).isTrue()

        val clockAfter = buildTimeMs + (24 * 60 * 60 * 1000L) // 1 day after
        val isAfter = ClockIntegrityValidator.isClockBeforeBuildTime(
            currentEpochMs = clockAfter,
            buildTimestampEpochMs = buildTimeMs
        )
        assertThat(isAfter).isFalse()
    }

    /**
     * TC-REL-05: Research mode; phone clock 15 minutes from the stored server offset.
     * Expected: Screening blocked until corrected.
     */
    @Test
    fun testResearchClockDriftExceeded() {
        val fifteenMinutesDriftMs = 15 * 60 * 1000L
        val exceeded = ClockIntegrityValidator.isResearchClockDriftExceeded(fifteenMinutesDriftMs)
        assertThat(exceeded).isTrue()

        val fiveMinutesDriftMs = 5 * 60 * 1000L
        val acceptable = ClockIntegrityValidator.isResearchClockDriftExceeded(fiveMinutesDriftMs)
        assertThat(acceptable).isFalse()
    }

    /**
     * TC-REL-06: Enter a birth time after the phone's current time.
     * Expected: Refused.
     */
    @Test
    fun testBirthTimeInFutureRefused() {
        val now = 1791700000000L
        val futureBirth = now + 3600000L // 1 hour into future
        val pastBirth = now - 3600000L   // 1 hour in past

        assertThat(ClockIntegrityValidator.isBirthTimeInFuture(futureBirth, now)).isTrue()
        assertThat(ClockIntegrityValidator.isBirthTimeInFuture(pastBirth, now)).isFalse()
    }

    /**
     * TC-UI-10: Open S10; "Phone time now" line shows the phone's current date and time.
     */
    @Test
    fun testPhoneTimeNowFormatting() {
        // 11 Oct 2026 12:30:00 UTC
        val epochMs = 1791721800000L
        val formatted = ClockIntegrityValidator.formatPhoneTimeNow(
            epochMs = epochMs,
            zoneId = ZoneId.of("UTC"),
            locale = Locale.ENGLISH
        )
        assertThat(formatted).isEqualTo("11 Oct 12:30 PM")
    }

    /**
     * Age in hours calculation: 52 hours.
     */
    @Test
    fun testAgeInHoursCalculation() {
        val now = 1791721800000L
        val birthTime = now - (52 * 3600 * 1000L) // 52 hours ago
        val ageHours = ClockIntegrityValidator.computeAgeInHours(birthTime, now)
        assertThat(ageHours).isEqualTo(52.0)
    }
}
