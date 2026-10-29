package com.kitalonlabs.neogold.core.security.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Privacy controls helper for screen protection and anti-overlay defences (doc 8 Section 6.1).
 */
object SecureScreenHelper {

    val SECURE_DESTINATIONS = setOf(
        "s14_rule_outcome", // asks for the register number, like S20
        "s20_result",
        "s21_log",
        "s22_session_detail",
        "r01_research_unlock", // the PIN is typed here
        "r02_study_home",
        "r03_participant_consent",
        "r04_capture_protocol",
        "r05_tsb_entry",
        "r06_session_complete",
        "r07_export_sync",
        "r08_enrollment" // shows the one-time enrollment code
    )

    /**
     * Applies or clears FLAG_SECURE on the given window.
     */
    fun setSecureScreen(window: Window, enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Prevents recents task snapshot previews on Android 13+ (API 33 and up).
     */
    fun setRecentsScreenshotEnabled(activity: Activity, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(enabled)
        }
    }

    /**
     * Sets filterTouchesWhenObscured on View hierarchy for API 26 to 30 overlay protection.
     */
    fun setFilterTouchesWhenObscured(view: View, enabled: Boolean = true) {
        view.filterTouchesWhenObscured = enabled
    }

    /**
     * Sets filterTouchesWhenObscured on Window decor view for API 26 to 30 overlay protection.
     */
    fun setFilterTouchesWhenObscured(window: Window, enabled: Boolean = true) {
        window.decorView.filterTouchesWhenObscured = enabled
    }

    /**
     * Checks if a MotionEvent is obscured by a window overlay (API 26 to 30 tapjacking defence).
     */
    fun isTouchEventObscured(event: MotionEvent): Boolean {
        val flags = event.flags
        val badFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        return (flags and badFlags) != 0
    }

    /**
     * Checks if a destination route contains register numbers or research data.
     */
    fun isDestinationSecure(route: String?): Boolean {
        if (route == null) return false
        return SECURE_DESTINATIONS.contains(route)
    }

    fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
