package com.kitalonlabs.neogold.core.security

import android.app.Activity
import android.view.MotionEvent
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.privacy.SecureScreenHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureScreenHelperTest {

    @Test
    fun secureDestinations_matchDoc8AndTC_PRIV_02() {
        // S21, S22, R02 to R07 must be secure
        assertThat(SecureScreenHelper.isDestinationSecure("s21_log")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("s22_session_detail")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("s20_result")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r02_study_home")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r03_participant_consent")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r04_capture_protocol")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r05_tsb_entry")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r06_session_complete")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r07_export_sync")).isTrue()

        // Non-sensitive screens are not secure
        assertThat(SecureScreenHelper.isDestinationSecure("s01_language")).isFalse()
        assertThat(SecureScreenHelper.isDestinationSecure("s09_home")).isFalse()
        assertThat(SecureScreenHelper.isDestinationSecure("s16_camera")).isFalse()
    }

    @Test
    fun s14RuleOutcome_isSecure_becauseItTakesTheRegisterNumber_TC_PRIV_02() {
        assertThat(SecureScreenHelper.isDestinationSecure("s14_rule_outcome")).isTrue()
    }

    @Test
    fun pinEntryAndEnrollment_areSecure_becauseThePinAndTheEnrollmentCodeAreTypedThere_TC_PRIV_02() {
        assertThat(SecureScreenHelper.isDestinationSecure("r01_research_unlock")).isTrue()
        assertThat(SecureScreenHelper.isDestinationSecure("r08_enrollment")).isTrue()
    }

    @Test
    fun setSecureScreen_togglesWindowFlagSecure_TC_PRIV_02() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val window = activity.window

        // Initially no FLAG_SECURE
        val initialFlags = window.attributes.flags
        assertThat((initialFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0).isFalse()

        // Enable secure screen
        SecureScreenHelper.setSecureScreen(window, true)
        val securedFlags = window.attributes.flags
        assertThat((securedFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0).isTrue()

        // Disable secure screen
        SecureScreenHelper.setSecureScreen(window, false)
        val clearedFlags = window.attributes.flags
        assertThat((clearedFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0).isFalse()
    }

    @Test
    fun isTouchEventObscured_detectsObscuredFlags() {
        val normalEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        assertThat(SecureScreenHelper.isTouchEventObscured(normalEvent)).isFalse()

        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 0f
                y = 0f
            }
        )

        val obscuredEvent = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_OBSCURED
        )
        assertThat(SecureScreenHelper.isTouchEventObscured(obscuredEvent)).isTrue()

        val partiallyObscuredEvent = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        )
        assertThat(SecureScreenHelper.isTouchEventObscured(partiallyObscuredEvent)).isTrue()
    }
}
