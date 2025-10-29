package com.example.vitality.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 🎨 Palette colori chiara e scura
private val LightColorScheme = lightColorScheme(
    primary = LimeAccent,
    secondary = TealGlow,
    background = DeepGray,
    surface = DarkBlue,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = TealGlow,
    secondary = LimeAccent,
    background = DarkBlue,
    surface = DeepGray,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

/**
 * 🔹 Tema principale Vitality
 * Applica palette, font e stili a tutto il progetto.
 */
@Composable
fun VitalityAppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
