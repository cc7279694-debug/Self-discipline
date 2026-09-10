package com.guanyi.mirra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MirraColors = lightColorScheme(
    primary = Color(0xFF315C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E8DB),
    onPrimaryContainer = Color(0xFF153A2E),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF20231F),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF20231F),
    surfaceVariant = Color(0xFFE7E3DA),
    onSurfaceVariant = Color(0xFF5E625D),
)

@Composable
fun MirraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MirraColors,
        content = content,
    )
}
