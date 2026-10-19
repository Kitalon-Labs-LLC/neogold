package com.kitalonlabs.neogold.core.designsystem

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.designsystem.util.ClinicalNumberFormatter
import org.junit.Test

/**
 * TC-A11Y-03: Western digits (NFR-06, doc 6 Section 12).
 * Verifies that clinical numbers (bilirubin, hours, weeks) always format in Western ASCII digits
 * in both languages so Bangladeshi clinical registers and lab values avoid misreading.
 */
class ClinicalNumberFormatterTest {

    @Test
    fun testClinicalDecimalFormattedInWesternDigits() {
        val formatted = ClinicalNumberFormatter.formatClinicalDecimal(15.42)
        assertThat(formatted).isEqualTo("15.4")
        assertThat(formatted.all { it in "0123456789." }).isTrue()
    }

    @Test
    fun testClinicalIntegerFormattedInWesternDigits() {
        val hours = ClinicalNumberFormatter.formatClinicalInteger(52)
        assertThat(hours).isEqualTo("52")
        assertThat(hours.all { it in "0123456789" }).isTrue()

        val weeks = ClinicalNumberFormatter.formatClinicalInteger(38)
        assertThat(weeks).isEqualTo("38")
    }

    @Test
    fun testClinicalRangeFormattedInWesternDigits() {
        val range = ClinicalNumberFormatter.formatClinicalRange(11.0, 14.0, "mg/dL")
        assertThat(range).isEqualTo("11.0 to 14.0 mg/dL")
    }

    @Test
    fun testBanglaDigitConversionForNonClinicalText() {
        val bnDigits = ClinicalNumberFormatter.toBanglaDigits("Screening 123")
        assertThat(bnDigits).isEqualTo("Screening ১২৩")

        val reverted = ClinicalNumberFormatter.toWesternDigits(bnDigits)
        assertThat(reverted).isEqualTo("Screening 123")
    }
}
