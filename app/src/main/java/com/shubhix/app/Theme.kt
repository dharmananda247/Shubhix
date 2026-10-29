package com.shubhix.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Charcoal = Color(0xFF18181B)
val Blue = Color(0xFF3B82F6)
val Panel = Color(0xFF27272A)
val Muted = Color(0xFFA1A1AA)

@Composable
fun ShubhixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, onPrimary = Color.White,
            background = Charcoal, onBackground = Color.White,
            surface = Panel, onSurface = Color.White,
            surfaceVariant = Panel, onSurfaceVariant = Muted,
            secondaryContainer = Blue.copy(alpha = 0.3f), onSecondaryContainer = Color.White
        ),
        content = content
    )
}
