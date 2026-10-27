@file:Suppress("MagicNumber") // the accepted register number lengths are the validation rule

package com.kitalonlabs.neogold.core.common.validation

/**
 * Validates facility register numbers according to PRD Section 11, FR-23, and doc 8 Section 14 (T15).
 *
 * Rules:
 * - Optional field: null or blank is valid.
 * - Accepts letters, digits, "/" and "-", up to 20 characters.
 * - Rejects anything longer than 20 characters.
 * - Rejects 17-digit numbers (birth registration number or 17-digit NID).
 * - Rejects 10-digit numbers (Smart NID).
 * - Rejects 13-digit numbers (Old NID).
 * - Rejects 11-digit mobile numbers starting with 01.
 * - Rejects names and any characters other than letters, digits, "/" and "-".
 */
object RegisterNumberValidator {

    private val ALLOWED_CHARS_REGEX = Regex("^[A-Za-z0-9/-]{1,20}$")
    private val DIGITS_ONLY_REGEX = Regex("^[0-9]+$")
    private val BANGLADESH_MOBILE_REGEX = Regex("^01[0-9]{9}$")

    sealed class Result {
        data object Valid : Result()
        data class Invalid(val messageResName: String = "register_rejected") : Result()
    }

    /**
     * Validates a candidate facility register number.
     */
    @Suppress("ReturnCount") // validation is a chain of early returns, one per rule
    fun validate(input: String?): Result {
        if (input.isNullOrBlank()) {
            return Result.Valid
        }

        val trimmed = input.trim()

        if (trimmed.length > 20) {
            return Result.Invalid()
        }

        if (!ALLOWED_CHARS_REGEX.matches(trimmed)) {
            return Result.Invalid()
        }

        // Check if entirely digits
        if (DIGITS_ONLY_REGEX.matches(trimmed)) {
            val length = trimmed.length
            // 10-digit NID, 13-digit NID, 17-digit birth registration / NID
            if (length == 10 || length == 13 || length == 17) {
                return Result.Invalid()
            }
        }

        // Check 11-digit mobile number starting with 01
        if (BANGLADESH_MOBILE_REGEX.matches(trimmed)) {
            return Result.Invalid()
        }

        return Result.Valid
    }

    fun isValid(input: String?): Boolean = validate(input) is Result.Valid
}
