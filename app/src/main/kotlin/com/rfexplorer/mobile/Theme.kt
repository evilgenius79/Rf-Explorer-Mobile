package com.rfexplorer.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark, instrument-style palette: green trace on near-black, amber peak marker.
internal val TraceGreen = Color(0xFF35E06A)
internal val PeakAmber = Color(0xFFFFB300)
internal val GridGray = Color(0xFF2A2F36)
private val Background = Color(0xFF0E1116)
private val Surface = Color(0xFF161B22)

private val DarkColors = darkColorScheme(
    primary = TraceGreen,
    secondary = PeakAmber,
    background = Background,
    surface = Surface,
)

@Composable
fun RfExplorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
