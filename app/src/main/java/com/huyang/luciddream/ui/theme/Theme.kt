package com.huyang.luciddream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF122B55),
    primaryContainer = Color(0xFF263B65),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBFC6DC),
    background = Color(0xFF101521),
    surface = Color(0xFF101521),
    surfaceVariant = Color(0xFF202735),
    onBackground = Color(0xFFE1E7F5),
    onSurface = Color(0xFFE1E7F5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF405A8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE1E7F5),
    onBackground = Color(0xFF191C20),
    onSurface = Color(0xFF191C20),
)

@Composable
fun LucidDreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LucidTypography,
        content = content,
    )
}
