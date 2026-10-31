package com.kitalonlabs.neogold.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class TriageColors(
    val urgentSolid: Color,
    val urgentTint: Color,
    val urgentText: Color,
    val bloodTestSolid: Color,
    val bloodTestTint: Color,
    val bloodTestText: Color,
    val recheckSolid: Color,
    val recheckTint: Color,
    val recheckText: Color,
    val noActionSolid: Color,
    val noActionTint: Color,
    val noActionText: Color,
    val experimentalTint: Color,
    val experimentalText: Color,
    val cameraOverlay: Color
)
