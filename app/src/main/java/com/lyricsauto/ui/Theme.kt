package com.lyricsauto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFD166)
val Ink = Color(0xFF0B0B10)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Accent,
    background = Ink,
    surface = Color(0xFF15141C),
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun LyricsAutoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
