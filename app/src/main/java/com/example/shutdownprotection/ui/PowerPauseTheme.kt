package com.example.shutdownprotection.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightPalette = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFAAF1DF), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF415C73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEBF5), onSecondaryContainer = Color(0xFF142B3D),
    background = Color(0xFFF6F8FA), surface = Color(0xFFF6F8FA),
    onBackground = Color(0xFF142B3D), onSurface = Color(0xFF142B3D),
    onSurfaceVariant = Color(0xFF4D616A), surfaceContainerLow = Color.White,
    outline = Color(0xFF72858D), outlineVariant = Color(0xFFD3DEE3)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8DDAC8), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFFAAF1DF),
    secondary = Color(0xFFAFC9DE), onSecondary = Color(0xFF153246),
    secondaryContainer = Color(0xFF263E50), onSecondaryContainer = Color(0xFFDEEBF5),
    background = Color(0xFF101A22), surface = Color(0xFF101A22),
    onBackground = Color(0xFFE2EBF0), onSurface = Color(0xFFE2EBF0),
    onSurfaceVariant = Color(0xFFB6C7CE), surfaceContainerLow = Color(0xFF19262F),
    outline = Color(0xFF81979F), outlineVariant = Color(0xFF364952)
)

@Composable
fun PowerPauseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
