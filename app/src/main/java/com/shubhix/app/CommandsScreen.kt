package com.shubhix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Cmd(val text: String, val online: Boolean = false, val prompt: String = text)
private data class Group(val title: String, val cmds: List<Cmd>)

private val groups = listOf(
    Group("Apps", listOf(Cmd("Open WhatsApp"), Cmd("Open camera"), Cmd("Open Chrome"), Cmd("Open Wi-Fi settings"))),
    Group("Media", listOf(Cmd("Play Arijit Singh songs", true), Cmd("Pause"), Cmd("Next song"), Cmd("Volume up"))),
    Group("Screen", listOf(Cmd("Scroll down"), Cmd("Go back"), Cmd("Go home"), Cmd("Recent apps"), Cmd("Take a screenshot"))),
    Group("Tools", listOf(Cmd("Torch on"), Cmd("What time is it"), Cmd("Battery level"), Cmd("Daily briefing"), Cmd("Read my notifications"), Cmd("Set a 10 minute timer"), Cmd("Call Mom"), Cmd("Write a Python calculator", true))),
    Group("Phone tasks (AI)", listOf(
        Cmd("Open YouTube, search bollywood songs and play it", true),
        Cmd("Open WhatsApp and message Mom hi", true),
        Cmd("Open settings and find battery usage", true)
    )),
    Group("Study", listOf(
        Cmd("Quiz me: Tally Prime", true, "Quiz me with 5 multiple-choice questions on Tally Prime. Ask one question at a time, wait for my answer, then say if it is right, explain briefly, and ask the next one."),
        Cmd("Practice journal entries", true, "Give me one journal entry practice transaction at a time (Indian accounting). Wait for my answer, then check it and explain the debit and credit rule."),
        Cmd("Explain GST simply", true, "Explain GST in India in simple steps with one small example, covering CGST, SGST and IGST."),
        Cmd("SBI Clerk reasoning quiz", true, "Ask me one SBI Clerk prelims reasoning question at a time with four options. Wait for my answer, check it and explain the method."),
        Cmd("SBI Clerk maths quiz", true, "Ask me one SBI Clerk prelims quantitative aptitude question at a time with four options. Wait for my answer, check it and show a quick method."),
        Cmd("SBI Clerk English quiz", true, "Ask me one SBI Clerk prelims English language question at a time (grammar, cloze or error spotting). Wait for my answer, then explain.")
    ))
)

private val customTypes = listOf(
    "open_app" to "Open app", "open_url" to "Website", "call" to "Call", "youtube" to "YouTube", "ask_ai" to "Ask AI"
)

@Composable
fun CommandsScreen(vm: MainVM, onTry: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Commands", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text("Tap one to try it. Offline ones work without internet.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MY COMMANDS", color = Glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                TextButton({ adding = true }) { Text("+ Add") }
            }
        }
        if (vm.custom.isEmpty()) {
            item {
                Text(
                    "Make your own shortcut, for example say \"my playlist\" to search YouTube, or \"study time\" to ask the AI for a quiz.",
                    color = Muted, fontSize = 13.sp
                )
            }
        }
        items(vm.custom.toList()) { c -> CustomRow(c, { onTry(c.phrase) }, { vm.removeCustom(c) }) }
        groups.forEach { g ->
            item { Text(g.title.uppercase(), color = Glow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 12.dp)) }
            items(g.cmds) { c -> CommandRow(c) { onTry(c.prompt) } }
        }
        item { Box(Modifier.padding(8.dp)) }
    }
    if (adding) AddCustomDialog({ adding = false }) { vm.addCustom(it); adding = false }
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

@Composable
private fun CustomRow(c: CustomCmd, onRun: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val label = customTypes.firstOrNull { it.first == c.type }?.second ?: c.type
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape)
            .clickable(onClick = onRun).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("“${c.phrase}”", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("$label · ${c.value}", color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        TextButton(onDelete) { Text("Delete", color = Bad, fontSize = 12.sp) }
    }
}

@Composable
private fun AddCustomDialog(onDismiss: () -> Unit, onSave: (CustomCmd) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("open_app") }
    var value by remember { mutableStateOf("") }
    val hint = when (type) {
        "open_app" -> "App name, e.g. WhatsApp"
        "open_url" -> "Link, e.g. nexgenbyte.blogspot.com"
        "call" -> "Contact name or number"
        "youtube" -> "What to search on YouTube"
        else -> "What should the AI do? e.g. Quiz me with 5 Tally questions"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(phrase, { phrase = it }, label = { Text("When I say…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    customTypes.forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) }) }
                }
                OutlinedTextField(value, { value = it }, label = { Text(hint) }, minLines = if (type == "ask_ai") 3 else 1, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = phrase.trim().length >= 3 && value.isNotBlank(),
                onClick = { onSave(CustomCmd(phrase.trim(), type, value.trim())) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
