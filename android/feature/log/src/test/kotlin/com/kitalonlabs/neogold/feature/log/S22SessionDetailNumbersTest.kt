package com.kitalonlabs.neogold.feature.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * TC-A11Y-03, TC-ONB-04 (BUG-2026-051): clinical numbers on the session detail screen are formatted in Western digits whatever
 * the phone's language. A render of S22 under a Bangla locale needs a Compose UI test library, which is not approved; this reads
 * the source the way the other wiring guards do.
 */
class S22SessionDetailNumbersTest {

    private val source: String by lazy {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        File(dir, "feature/log/src/main/kotlin/com/kitalonlabs/neogold/feature/log/S22SessionDetailScreen.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun noClinicalNumberIsFormattedWithTheDefaultLocale() {
        // the date is formatted with a date formatter on purpose; numbers never go through a format string
        assertThat(source).doesNotContain("String.format(")
        assertThat(source).doesNotContain("%d")
        assertThat(source).doesNotContain("\"%.")
    }

    @Test
    fun theEstimateIntervalThresholdAgeAndGestationUseTheClinicalFormatter() {
        for (value in listOf("estimate", "low", "high", "threshold")) {
            assertThat(source).contains("ClinicalNumberFormatter.formatClinicalDecimal($value)")
        }
        assertThat(source).contains("ClinicalNumberFormatter.formatClinicalInteger(session.gestationalAgeWeeks)")
        assertThat(source).contains("ClinicalNumberFormatter.formatClinicalInteger(session.ageHours.toInt())")
    }

    @Test
    fun everyLabelComesFromAStringResource() {
        for (label in listOf(
            "detail_date_time",
            "detail_estimated_bilirubin",
            "detail_treatment_threshold",
            "detail_session_id"
        )) {
            assertThat(source).contains("R.string.$label")
        }
    }
}
