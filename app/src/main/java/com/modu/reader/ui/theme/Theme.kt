package com.modu.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6F62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2E7DF),
    onPrimaryContainer = Color(0xFF0E332C),
    secondary = Color(0xFF8C6A3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0E2C8),
    onSecondaryContainer = Color(0xFF3A2A12),
    background = Color(0xFFF7F3EA),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFDFBF6),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEDE6D7),
    onSurfaceVariant = Color(0xFF6B6152),
    outline = Color(0xFFCFC5B2),
    outlineVariant = Color(0xFFE3DBC9),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD3C4),
    onPrimary = Color(0xFF0B2A24),
    primaryContainer = Color(0xFF25504A),
    onPrimaryContainer = Color(0xFFC9EDE3),
    secondary = Color(0xFFD9BB8C),
    onSecondary = Color(0xFF2E2210),
    secondaryContainer = Color(0xFF473820),
    onSecondaryContainer = Color(0xFFF2E0C2),
    background = Color(0xFF14140F),
    onBackground = Color(0xFFE8E2D6),
    surface = Color(0xFF1B1B15),
    onSurface = Color(0xFFE8E2D6),
    surfaceVariant = Color(0xFF2A2A21),
    onSurfaceVariant = Color(0xFFB6AE9D),
    outline = Color(0xFF4A4739),
    outlineVariant = Color(0xFF332F26),
    error = Color(0xFFF2B8B5)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MoDuTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
