package com.example.vitality.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BluePrimaryDark,
    onPrimaryContainer = Color.White,

    secondary = CyanSecondary,
    onSecondary = Color.Black,

    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),

    surface = Color.White,
    onSurface = Color(0xFF111827),

    surfaceVariant = Color(0xFFE3E8EF),
    onSurfaceVariant = Color(0xFF475569),

    outline = Color(0xFF94A3B8),

    error = ErrorRed
)

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    onPrimary = Color.Black,
    primaryContainer = BluePrimaryDark,
    onPrimaryContainer = Color.White,

    secondary = CyanSecondary,
    onSecondary = Color.Black,

    background = BackgroundDark,
    onBackground = Color.White,

    surface = SurfaceDark,
    onSurface = Color.White,

    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFF94A3B8),

    outline = OutlineDark,

    error = ErrorRed
)

@Composable
fun VitalityAppTheme(
    darkTheme: Boolean = true,   // Dark-first experience
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = VitalityTypography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
