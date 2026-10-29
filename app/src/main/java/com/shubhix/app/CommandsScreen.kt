package com.shubhix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Cmd(val text: String, val online: Boolean = false)
private data class Group(val title: String, val cmds: List<Cmd>)

private val groups = listOf(
    Group("Apps", listOf(Cmd("Open WhatsApp"), Cmd("Open camera"), Cmd("Open Chrome"))),
    Group("Media", listOf(Cmd("Play Arijit Singh songs", true), Cmd("Pause"), Cmd("Next song"), Cmd("Volume up"))),
    Group("Screen", listOf(Cmd("Scroll down"), Cmd("Go back"), Cmd("Go home"), Cmd("Recent apps"))),
    Group("Tools", listOf(Cmd("Torch on"), Cmd("Set a 10 minute timer"), Cmd("Call Mom"), Cmd("Write a Python calculator", true)))
)

@Composable
fun CommandsScreen(onTry: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Commands", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text("Tap one to try it. Offline ones work without internet.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        groups.forEach { g ->
            item { Text(g.title.uppercase(), color = Glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 12.dp)) }
            items(g.cmds) { c -> CommandRow(c) { onTry(c.text) } }
        }
        item { Box(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun CommandRow(c: Cmd, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, BorderC, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            if (c.online) "Online" else "Offline",
            color = if (c.online) accent else Good, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
    }
}
