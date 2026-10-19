package com.kitalonlabs.neogold.core.designsystem.util

import java.util.Locale

/**
 * Number formatter respecting clinical safety rules (doc 6 Section 12, TC-A11Y-03).
 *
 * In Bangladeshi hospital wards, pathology laboratories and registers, clinical numbers
 * (bilirubin estimates, gestational age, age in hours, thresholds) are universally
 * reported in Western digits to avoid ambiguity between Bengali glyphs.
 *
 * By default, clinical numbers remain in Western digits regardless of UI language.
 */
object ClinicalNumberFormatter {

    private val BANGLA_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    private val WESTERN_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

    /**
     * Formats a clinical decimal number (e.g. bilirubin estimate, threshold)
     * strictly in Western ASCII digits.
     */
    fun formatClinicalDecimal(value: Double, decimals: Int = 1): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    fun formatDecimal(value: Double, decimals: Int = 1): String = formatClinicalDecimal(value, decimals)

    /**
     * Formats a clinical integer (e.g. age in hours, gestational age in weeks)
     * strictly in Western ASCII digits.
     */
    fun formatClinicalInteger(value: Int): String {
        return String.format(Locale.US, "%d", value)
    }

    fun formatInteger(value: Int): String = formatClinicalInteger(value)

    /**
     * Formats an estimated range (e.g. "11 to 14 mg/dL") in Western digits.
     */
    fun formatClinicalRange(low: Double, high: Double, unit: String = "mg/dL"): String {
        return "${formatClinicalDecimal(low)} to ${formatClinicalDecimal(high)} $unit"
    }

    /**
     * Converts a string containing Western digits to Bangla digits if requested for non-clinical display.
     */
    fun toBanglaDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = ch - '0'
            if (idx in 0..9) {
                sb.append(BANGLA_DIGITS[idx])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Converts a string containing Bangla digits back to Western digits.
     */
    fun toWesternDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = BANGLA_DIGITS.indexOf(ch)
            if (idx >= 0) {
                sb.append(WESTERN_DIGITS[idx])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
