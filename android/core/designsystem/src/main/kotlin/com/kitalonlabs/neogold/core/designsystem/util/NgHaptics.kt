package com.kitalonlabs.neogold.core.designsystem.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Micro-haptics for the screening flow (doc 6 Section 3.2: a tick when the card is read, a
 * double tick when a photo is captured).
 *
 * Every call goes through [View.performHapticFeedback] with no flags, so:
 * - the system touch-feedback / haptics switch is honoured (a phone with haptics turned off
 *   stays silent, which matters in a ward at night);
 * - the manufacturer's haptic engine picks the waveform, so the tick feels native on a Redmi
 *   and on an Android Go device rather than like a fixed buzz;
 * - no VIBRATE permission and no service binding is needed.
 *
 * Keep these to ticks and confirms. A clinical flow that vibrates on every row it draws stops
 * being information and becomes noise.
 */
class NgHaptics internal constructor(private val view: View) {

    /** A light key-press tick: buttons, chips, question answers. */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A firmer confirmation: the card was read and verified. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
    )

    /** A refusal: a capture the quality gate rejected. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    )

    /** One tick, as a clock would. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /**
     * Two ticks about 70 ms apart — the "photo taken" pattern doc 6 asks for. Distinct from
     * [confirm] so a health worker can tell a capture from a card read without looking.
     */
    fun doubleTick() {
        tick()
        view.postDelayed({ tick() }, DOUBLE_TICK_GAP_MS)
    }

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    private companion object {
        const val DOUBLE_TICK_GAP_MS = 70L
    }
}

@Composable
fun rememberNgHaptics(): NgHaptics {
    val view = LocalView.current
    return remember(view) { NgHaptics(view) }
}
