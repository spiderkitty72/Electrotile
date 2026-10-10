package com.electrotile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0B1410)
    val Surface = Color(0xFF14231C)
    val Tile = Color(0xFF1B2E25)
    val TileFixed = Color(0xFF101D17)
    val TileEdge = Color(0xFF2A4236)
    val WireIdle = Color(0xFF5F7369)
    val WireLive = Color(0xFFFFC94D)
    val Glow = Color(0xFFFFE082)
    val Charge = Color(0xFF4DD0E1)
    val Danger = Color(0xFFFF5252)
    val Success = Color(0xFF69F0AE)
    val TextMuted = Color(0xFF9DB3A8)
}

private val Colors = darkColorScheme(
    primary = Palette.WireLive,
    onPrimary = Color(0xFF2B1D00),
    secondary = Palette.Charge,
    background = Palette.Background,
    onBackground = Color(0xFFE6F0EB),
    surface = Palette.Surface,
    onSurface = Color(0xFFE6F0EB),
    surfaceVariant = Palette.Tile,
    onSurfaceVariant = Palette.TextMuted,
    error = Palette.Danger,
)

@Composable
fun ElectrotileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
