package com.shubhix.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Avatar(persona: String, size: Dp, ring: Boolean = false, modifier: Modifier = Modifier) {
    val res = if (persona == "DJ") R.drawable.avatar_dj else R.drawable.avatar_shubhu
    var m = modifier.size(size).clip(CircleShape)
    if (ring) m = m.border(2.dp, accentFor(persona), CircleShape)
    Image(painterResource(res), persona, modifier = m, contentScale = ContentScale.Crop)
}

/** Avatar with a softly pulsing glow behind it. */
@Composable
fun GlowAvatar(persona: String, size: Dp) {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(0.12f, 0.42f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "a")
    val sc by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "s")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(sc).clip(CircleShape).background(accentFor(persona).copy(alpha = a)))
        Avatar(persona, size, ring = true)
    }
}

@Composable
fun Waveform(color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    Row(
        Modifier.height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(7) { i ->
            val h by t.animateFloat(
                6f, 28f,
                infiniteRepeatable(tween(380 + i * 70, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar$i"
            )
            Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun StatusDot(ok: Boolean, size: Dp = 8.dp, color: Color? = null) {
    Box(Modifier.size(size).clip(CircleShape).background(color ?: if (ok) Good else Bad))
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .border(1.dp, BorderC, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title.uppercase(), color = Glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        content()
    }
}
