package com.shubhix.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Holds which theme is active. The colours below read it, so changing it repaints the whole app. */
object Palette {
    var gaming by mutableStateOf(true)
}

// ---- Design tokens ----
val Bg: Color get() = if (Palette.gaming) Color(0xFF0A0612) else Color(0xFF070312)
val Panel: Color get() = if (Palette.gaming) Color(0xFF130B22) else Color(0xFF14091F)
val BorderC: Color get() = if (Palette.gaming) Color(0xFF3B2A66) else Color(0xFF2B1F3F)
val Muted: Color get() = if (Palette.gaming) Color(0xFFA1A1C5) else Color(0xFFA1A1AA)
val Glow: Color get() = if (Palette.gaming) Color(0xFFA855F7) else Color(0xFFC084FC)
val Hot: Color get() = if (Palette.gaming) Color(0xFFD946EF) else Color(0xFFC084FC)
val DjColor: Color get() = if (Palette.gaming) Color(0xFF6366F1) else Color(0xFF7C3AED)
val ShubhuColor: Color get() = if (Palette.gaming) Color(0xFFF472B6) else Color(0xFFE879F9)
val Cyan = Color(0xFF22D3EE)
val Good = Color(0xFF34D399)
val Bad = Color(0xFFF87171)

fun accentFor(persona: String) = if (persona == "DJ") DjColor else ShubhuColor

@Composable
fun ShubhixTheme(accent: Color, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.3f) Color(0xFF1B0A2A) else Color.White,
            background = Bg, onBackground = Color.White,
            surface = Panel, onSurface = Color.White,
            surfaceVariant = Panel, onSurfaceVariant = Muted,
            secondaryContainer = accent.copy(alpha = 0.25f), onSecondaryContainer = Color.White,
            outline = BorderC
        ),
        content = content
    )
}
