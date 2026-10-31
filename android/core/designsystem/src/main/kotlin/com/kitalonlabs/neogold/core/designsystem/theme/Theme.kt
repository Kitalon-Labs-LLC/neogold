package com.kitalonlabs.neogold.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalTriageColors = staticCompositionLocalOf {
    LightTriageColors
}

val LightTriageColors = TriageColors(
    urgentSolid = TriageUrgentSolid,
    urgentTint = TriageUrgentTintLight,
    urgentText = TriageUrgentTextLight,
    bloodTestSolid = TriageBloodTestSolid,
    bloodTestTint = TriageBloodTestTintLight,
    bloodTestText = TriageBloodTestTextLight,
    recheckSolid = TriageRecheckSolid,
    recheckTint = TriageRecheckTintLight,
    recheckText = TriageRecheckTextLight,
    noActionSolid = TriageNoActionSolid,
    noActionTint = TriageNoActionTintLight,
    noActionText = TriageNoActionTextLight,
    experimentalTint = ExperimentalLabelTint,
    experimentalText = ExperimentalLabelText,
    cameraOverlay = CameraScreenOverlay
)

val DarkTriageColors = TriageColors(
    urgentSolid = TriageUrgentSolid,
    urgentTint = TriageUrgentTintDark,
    urgentText = TriageUrgentTextDark,
    bloodTestSolid = TriageBloodTestSolid,
    bloodTestTint = TriageBloodTestTintDark,
    bloodTestText = TriageBloodTestTextDark,
    recheckSolid = TriageRecheckSolid,
    recheckTint = TriageRecheckTintDark,
    recheckText = TriageRecheckTextDark,
    noActionSolid = TriageNoActionSolid,
    noActionTint = TriageNoActionTintDark,
    noActionText = TriageNoActionTextDark,
    experimentalTint = ExperimentalLabelTint,
    experimentalText = ExperimentalLabelText,
    cameraOverlay = CameraScreenOverlay
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = DarkBg,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    outline = DarkOutline,
    error = DarkError
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    background = LightBg,
    surface = LightSurface,
    onPrimary = Color.White,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    outline = LightOutline,
    error = LightError
)

@Composable
fun NeoGoldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val triageColors = if (darkTheme) DarkTriageColors else LightTriageColors

    CompositionLocalProvider(LocalTriageColors provides triageColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NeoGoldTypography,
            content = content
        )
    }
}
