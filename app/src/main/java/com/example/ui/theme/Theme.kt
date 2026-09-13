package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AngiDarkColorScheme = darkColorScheme(
    primary = AngiPrimary,
    onPrimary = AngiOnPrimary,
    primaryContainer = AngiPrimaryContainer,
    onPrimaryContainer = AngiOnPrimaryContainer,
    secondary = AngiSecondary,
    onSecondary = AngiOnSecondary,
    secondaryContainer = AngiSecondaryContainer,
    onSecondaryContainer = AngiOnSecondaryContainer,
    tertiary = AngiTertiary,
    onTertiary = AngiOnTertiary,
    background = AngiDarkBackground,
    onBackground = AngiTextPrimary,
    surface = AngiDarkSurface,
    onSurface = AngiTextPrimary,
    surfaceVariant = AngiDarkSurfaceVariant,
    onSurfaceVariant = AngiTextSecondary,
    outline = AngiBorder,
    error = AngiError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AngiDarkColorScheme,
        typography = Typography,
        content = content
    )
}
