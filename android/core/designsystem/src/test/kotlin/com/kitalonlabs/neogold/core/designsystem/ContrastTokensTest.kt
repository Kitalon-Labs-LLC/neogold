package com.kitalonlabs.neogold.core.designsystem

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.kitalonlabs.neogold.core.designsystem.theme.*
import org.junit.Test
import kotlin.math.pow

/**
 * TC-UI-06: Contrast test (NFR-07).
 * Automated calculation of WCAG 2.x contrast ratio for every text and background token pair
 * in doc 6 Section 2.1. Every pair must meet or exceed 4.5:1.
 */
class ContrastTokensTest {

    @Test
    fun testAllLightModeTokenPairsMeetContrast() {
        // Body text on background (16.6:1)
        assertContrastExceeds(LightTextPrimary, LightBg, 4.5, "Light text.primary on bg")
        assertContrastExceeds(LightTextPrimary, LightBg, 15.0, "Light text.primary on bg target 16.6:1")

        // Secondary text on background (7.6:1)
        assertContrastExceeds(LightTextSecondary, LightBg, 4.5, "Light text.secondary on bg")
        assertContrastExceeds(LightTextSecondary, LightBg, 7.0, "Light text.secondary on bg target 7.6:1")

        // White on primary button (7.7:1)
        assertContrastExceeds(Color.White, LightPrimary, 4.5, "White on LightPrimary")
        assertContrastExceeds(Color.White, LightPrimary, 7.0, "White on LightPrimary target 7.7:1")

        // White on error button (6.5:1)
        assertContrastExceeds(Color.White, LightError, 4.5, "White on LightError")
        assertContrastExceeds(Color.White, LightError, 6.0, "White on LightError target 6.5:1")
    }

    @Test
    fun testAllLightTriageTokenPairsMeetContrast() {
        // Urgent: White on Solid (6.5:1), Text on Tint (8.0:1)
        assertContrastExceeds(Color.White, TriageUrgentSolid, 4.5, "White on TriageUrgentSolid")
        assertContrastExceeds(TriageUrgentTextLight, TriageUrgentTintLight, 4.5, "Urgent text on tint light")
        assertContrastExceeds(TriageUrgentTextLight, TriageUrgentTintLight, 7.5, "Urgent text on tint light target 8.0:1")

        // Blood test: White on Solid (6.3:1), Text on Tint (8.3:1)
        assertContrastExceeds(Color.White, TriageBloodTestSolid, 4.5, "White on TriageBloodTestSolid")
        assertContrastExceeds(TriageBloodTestTextLight, TriageBloodTestTintLight, 4.5, "Blood test text on tint light")
        assertContrastExceeds(TriageBloodTestTextLight, TriageBloodTestTintLight, 7.5, "Blood test text on tint light target 8.3:1")

        // Recheck: White on Solid (7.3:1), Text on Tint (8.6:1)
        assertContrastExceeds(Color.White, TriageRecheckSolid, 4.5, "White on TriageRecheckSolid")
        assertContrastExceeds(TriageRecheckTextLight, TriageRecheckTintLight, 4.5, "Recheck text on tint light")
        assertContrastExceeds(TriageRecheckTextLight, TriageRecheckTintLight, 8.0, "Recheck text on tint light target 8.6:1")

        // No further action: White on Solid (7.6:1), Text on Tint (9.5:1)
        assertContrastExceeds(Color.White, TriageNoActionSolid, 4.5, "White on TriageNoActionSolid")
        assertContrastExceeds(TriageNoActionTextLight, TriageNoActionTintLight, 4.5, "No action text on tint light")
        assertContrastExceeds(TriageNoActionTextLight, TriageNoActionTintLight, 8.5, "No action text on tint light target 9.5:1")

        // Experimental label: Text on Tint (9.2:1)
        assertContrastExceeds(ExperimentalLabelText, ExperimentalLabelTint, 4.5, "Experimental text on tint")
        assertContrastExceeds(ExperimentalLabelText, ExperimentalLabelTint, 8.5, "Experimental text on tint target 9.2:1")
    }

    @Test
    fun testAllDarkModeTokenPairsMeetContrast() {
        // Dark primary text on dark bg (15.3:1)
        assertContrastExceeds(DarkTextPrimary, DarkBg, 4.5, "Dark text.primary on dark bg")
        assertContrastExceeds(DarkTextPrimary, DarkBg, 14.0, "Dark text.primary on dark bg target 15.3:1")

        // Dark secondary text on dark bg (8.6:1)
        assertContrastExceeds(DarkTextSecondary, DarkBg, 4.5, "Dark text.secondary on dark bg")
        assertContrastExceeds(DarkTextSecondary, DarkBg, 8.0, "Dark text.secondary on dark bg target 8.6:1")

        // Dark primary on dark bg (10.6:1)
        assertContrastExceeds(DarkPrimary, DarkBg, 4.5, "DarkPrimary on dark bg")
        assertContrastExceeds(DarkPrimary, DarkBg, 10.0, "DarkPrimary on dark bg target 10.6:1")

        // Dark triage pairs
        assertContrastExceeds(TriageUrgentTextDark, TriageUrgentTintDark, 4.5, "Urgent dark text on tint")
        assertContrastExceeds(TriageUrgentTextDark, TriageUrgentTintDark, 9.0, "Urgent dark text on tint target 9.7:1")

        assertContrastExceeds(TriageBloodTestTextDark, TriageBloodTestTintDark, 4.5, "Blood test dark text on tint")
        assertContrastExceeds(TriageBloodTestTextDark, TriageBloodTestTintDark, 8.5, "Blood test dark text on tint target 9.0:1")

        assertContrastExceeds(TriageRecheckTextDark, TriageRecheckTintDark, 4.5, "Recheck dark text on tint")
        assertContrastExceeds(TriageRecheckTextDark, TriageRecheckTintDark, 9.0, "Recheck dark text on tint target 9.6:1")

        assertContrastExceeds(TriageNoActionTextDark, TriageNoActionTintDark, 4.5, "No action dark text on tint")
        assertContrastExceeds(TriageNoActionTextDark, TriageNoActionTintDark, 9.5, "No action dark text on tint target 10.4:1")
    }

    @Test
    fun testCameraOverlayContrast() {
        // White text on camera dark overlay #2B2F33 (13.5:1)
        assertContrastExceeds(Color.White, CameraScreenOverlay, 4.5, "White on CameraScreenOverlay")
        assertContrastExceeds(Color.White, CameraScreenOverlay, 12.0, "White on CameraScreenOverlay target 13.5:1")
    }

    private fun assertContrastExceeds(foreground: Color, background: Color, minRatio: Double, description: String) {
        val ratio = calculateContrastRatio(foreground, background)
        assertWithMessage("Contrast ratio for $description: expected >= $minRatio, got $ratio")
            .that(ratio)
            .isAtLeast(minRatio)
    }

    /**
     * WCAG 2.x Relative Luminance and Contrast Ratio Calculation.
     * Formula: (L1 + 0.05) / (L2 + 0.05)
     */
    private fun calculateContrastRatio(c1: Color, c2: Color): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channelLuminance(c: Float): Double {
            val d = c.toDouble()
            return if (d <= 0.04045) {
                d / 12.92
            } else {
                ((d + 0.055) / 1.055).pow(2.4)
            }
        }
        val r = channelLuminance(color.red)
        val g = channelLuminance(color.green)
        val b = channelLuminance(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
