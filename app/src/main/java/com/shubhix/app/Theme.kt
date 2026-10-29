package com.shubhix.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// ---- Design tokens (one place for every colour in the app) ----
val Bg = Color(0xFF070312)        // app background, same near-black as the logo
val Panel = Color(0xFF14091F)     // cards, bubbles, input
val BorderC = Color(0xFF2B1F3F)   // hairline borders
val Muted = Color(0xFFA1A1AA)     // secondary text
val Glow = Color(0xFFC084FC)      // highlights, section titles
val DjColor = Color(0xFF7C3AED)   // DJ accent (violet-blue)
val ShubhuColor = Color(0xFFE879F9) // SHUBHU accent (pink)
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
