package com.aipro.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CameraDark = darkColorScheme(
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF151515),
    secondary = Color(0xFF8AC6D1),
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E22),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AiproTheme(content: @Composable () -> Unit) {
    // A camera UI is always dark; the flag is only kept so previews in the IDE stay honest.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = CameraDark, content = content)
}
