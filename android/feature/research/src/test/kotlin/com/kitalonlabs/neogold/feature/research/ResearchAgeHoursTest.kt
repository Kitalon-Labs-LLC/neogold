package com.kitalonlabs.neogold.feature.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.feature.research.ui.researchAgeHours
import org.junit.Test

/** The research age is worked out at save time, to a tenth of an hour (BUG-2026-081, BUG-2026-082, TC-UI-11). */
class ResearchAgeHoursTest {

    private val hour = 3_600_000L

    @Test
    fun theAgeGrowsWithTheTimeSpentOnTheCapturesNotWithTheTimeTheFormWasShown_BUG_2026_082() {
        val birth = 1_000_000_000L
        val whenTheFormWasShown = birth + 48 * hour
        val whenTheSessionIsSaved = whenTheFormWasShown + 20 * 60_000L // twenty minutes of captures

        assertThat(researchAgeHours(birth, whenTheFormWasShown)).isEqualTo(48.0)
        assertThat(researchAgeHours(birth, whenTheSessionIsSaved)).isEqualTo(48.3)
    }

    @Test
    fun theAgeIsAMultipleOfATenthOfAnHour_BUG_2026_081() {
        val age = researchAgeHours(0L, 47 * hour + 1_234_567L)

        assertThat(age * 10.0).isWithin(1e-9).of(kotlin.math.round(age * 10.0))
    }

    @Test
    fun theAgeIsRoundedDownTowardTheYoungerBand_NeverUpIntoTheNextOne() {
        // 47.96 hours is 47.9, not 48.0; 23.99 hours is 23.9, not 24.0
        assertThat(researchAgeHours(0L, (47.96 * hour).toLong())).isEqualTo(47.9)
        assertThat(researchAgeHours(0L, (23.99 * hour).toLong())).isEqualTo(23.9)
    }

    @Test
    fun aBirthTimeAfterNowGivesANegativeAgeTheRepositoryRefuses_EvenByAFewMinutes_BUG_2026_081() {
        assertThat(researchAgeHours(birthTimeMillis = 10 * hour, nowMillis = 9 * hour)).isLessThan(0.0)
        // three minutes ahead must not round to a zero age
        assertThat(researchAgeHours(birthTimeMillis = 10 * hour + 180_000L, nowMillis = 10 * hour)).isLessThan(0.0)
    }
}
