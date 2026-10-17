package com.kitalonlabs.neogold.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    onPrimary = LightBg,
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
