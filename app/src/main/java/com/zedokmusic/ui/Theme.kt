package com.zedokmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Zk {
    val bg = Color(0xFF0E0F12)
    val panel = Color(0xFF16181D)
    val panel2 = Color(0xFF1D2027)
    val line = Color(0xFF2A2E37)
    val text = Color(0xFFE6E8EC)
    val dim = Color(0xFF8A91A0)
    val accent = Color(0xFF3DDCC4)
    val danger = Color(0xFFFF5C5C)
    val warn = Color(0xFFFFC857)
}

@Composable
fun ZedokTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Zk.accent, onPrimary = Color(0xFF00201B), background = Zk.bg, surface = Zk.panel,
            onSurface = Zk.text, onBackground = Zk.text, surfaceVariant = Zk.panel2, onSurfaceVariant = Zk.dim,
            secondary = Color(0xFF8E7CFF), error = Zk.danger, outline = Zk.line,
        ),
        content = content,
    )
}
