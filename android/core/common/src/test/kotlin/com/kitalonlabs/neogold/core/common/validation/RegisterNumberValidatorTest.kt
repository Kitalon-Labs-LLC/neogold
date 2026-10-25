package com.kitalonlabs.neogold.core.common.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Verifies:
 * - TC-LOG-02: Register numbers accepted (FR-23)
 * - TC-LOG-03: Identifier patterns rejected (FR-23, T15)
 */
class RegisterNumberValidatorTest {

    /**
     * TC-LOG-02: Register numbers accepted.
     * Inputs: "12/345", "OPD-2027-15"
     * Expected: Accepted.
     */
    @Test
    fun testValidRegisterNumbersAccepted() {
        assertThat(RegisterNumberValidator.isValid("12/345")).isTrue()
        assertThat(RegisterNumberValidator.isValid("OPD-2027-15")).isTrue()
        assertThat(RegisterNumberValidator.isValid("REG-1")).isTrue()
        assertThat(RegisterNumberValidator.isValid("A/B/C-123")).isTrue()
        assertThat(RegisterNumberValidator.isValid(null)).isTrue() // optional
        assertThat(RegisterNumberValidator.isValid("")).isTrue()   // optional
        assertThat(RegisterNumberValidator.isValid("   ")).isTrue()// optional
    }

    /**
     * TC-LOG-03: Identifier patterns rejected.
     * Inputs:
     * - 17-digit number (e.g. 20011234567890123)
     * - 10-digit number (e.g. 1234567890)
     * - 13-digit number (e.g. 1985123456789)
     * - 11-digit mobile starting with 01 (e.g. 01712345678)
     * - 21 characters (e.g. A12345678901234567890)
     * - Full names or invalid characters (spaces, special symbols)
     * Expected: Each rejected with register_rejected.
     */
    @Test
    fun testIdentifierPatternsRejected() {
        // 17-digit birth registration / national ID
        val seventeenDigits = "20261234567890123"
        assertThat(RegisterNumberValidator.isValid(seventeenDigits)).isFalse()
        val res17 = RegisterNumberValidator.validate(seventeenDigits)
        assertThat(res17).isInstanceOf(RegisterNumberValidator.Result.Invalid::class.java)

        // 10-digit smart national ID
        val tenDigits = "1928374650"
        assertThat(RegisterNumberValidator.isValid(tenDigits)).isFalse()

        // 13-digit old national ID
        val thirteenDigits = "1985123456789"
        assertThat(RegisterNumberValidator.isValid(thirteenDigits)).isFalse()

        // 11-digit mobile number starting with 01
        val mobileNumber = "01712345678"
        assertThat(RegisterNumberValidator.isValid(mobileNumber)).isFalse()
        assertThat(RegisterNumberValidator.isValid("01899999999")).isFalse()

        // 21 characters (exceeds 20 char limit)
        val twentyOneChars = "A12345678901234567890"
        assertThat(twentyOneChars.length).isEqualTo(21)
        assertThat(RegisterNumberValidator.isValid(twentyOneChars)).isFalse()

        // Names with spaces or special characters
        assertThat(RegisterNumberValidator.isValid("Baby of Rokeya")).isFalse()
        assertThat(RegisterNumberValidator.isValid("MD. RAHIM")).isFalse()
        assertThat(RegisterNumberValidator.isValid("REG#123")).isFalse()
    }
}
