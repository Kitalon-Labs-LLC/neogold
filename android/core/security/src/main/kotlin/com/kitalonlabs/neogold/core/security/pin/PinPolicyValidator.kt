package com.kitalonlabs.neogold.core.security.pin

/**
 * Validation result for research PIN policy (doc 8 Section 6.2).
 */
sealed interface PinValidationResult {
    data object Valid : PinValidationResult
    data class Invalid(val error: PinPolicyError) : PinValidationResult
}

enum class PinPolicyError {
    TOO_SHORT,
    NON_DIGIT,
    REPEATED_DIGITS,
    SEQUENTIAL_DIGITS,
    COMMON_PIN
}

/**
 * Validates research mode PINs against security policy:
 * - Minimum 6 digits, digits only
 * - Rejects repeated digits (e.g. 000000, 111111)
 * - Rejects sequential digits (e.g. 123456, 654321)
 * - Rejects the top-100 most common PINs (e.g. 121212, 112233)
 */
object PinPolicyValidator {

    private const val MIN_LENGTH = 6

    // Top 100 most frequently used 6-digit PINs from statistical breach datasets (DataGenetics / OWASP)
    val TOP_100_PINS: Set<String> = setOf(
        "123456", "654321", "111111", "000000", "121212", "777777", "100410", "200000", "444444", "222222",
        "696969", "999999", "333333", "555555", "666666", "112233", "123123", "888888", "131313", "101010",
        "123321", "200100", "012345", "123450", "987654", "098765", "111222", "123654", "159753", "321654",
        "123789", "789456", "456123", "987123", "654987", "147258", "258369", "369258", "147852", "258147",
        "741852", "852963", "963852", "159357", "357159", "753951", "951753", "123465", "124578", "235689",
        "102030", "111000", "000111", "222111", "112211", "221122", "122122", "211211", "135791", "246802",
        "135790", "246801", "024680", "135792", "314159", "271828", "161803", "123457", "123458", "123459",
        "234567", "345678", "456789", "567890", "876543", "765432", "543210", "432109", "123412", "123423",
        "001122", "110011", "001100", "112200", "220011", "332211", "113355", "224466", "007007", "001001",
        "100100", "200200", "010101", "020202", "030303", "040404", "050505", "060606", "070707", "080808"
    )

    fun validate(pin: String): PinValidationResult {
        if (pin.length < MIN_LENGTH) {
            return PinValidationResult.Invalid(PinPolicyError.TOO_SHORT)
        }
        if (!pin.all { it.isDigit() }) {
            return PinValidationResult.Invalid(PinPolicyError.NON_DIGIT)
        }
        if (isRepeated(pin)) {
            return PinValidationResult.Invalid(PinPolicyError.REPEATED_DIGITS)
        }
        if (isSequential(pin)) {
            return PinValidationResult.Invalid(PinPolicyError.SEQUENTIAL_DIGITS)
        }
        if (pin in TOP_100_PINS) {
            return PinValidationResult.Invalid(PinPolicyError.COMMON_PIN)
        }
        return PinValidationResult.Valid
    }

    private fun isRepeated(pin: String): Boolean {
        val first = pin[0]
        return pin.all { it == first }
    }

    private fun isSequential(pin: String): Boolean {
        var ascending = true
        var descending = true
        for (i in 1 until pin.length) {
            val diff = pin[i] - pin[i - 1]
            if (diff != 1) ascending = false
            if (diff != -1) descending = false
        }
        return ascending || descending
    }
}
