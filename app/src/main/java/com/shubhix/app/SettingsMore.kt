package com.shubhix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- small helpers shared by the settings cards ----

fun openOverlaySettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { /* ignore */ }
}

fun openAppInfo(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { /* ignore */ }
}

/** Opens the battery optimisation list so Shubhix AI can be set to Unrestricted / Don't optimise. */
fun openBatterySettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        openAppInfo(ctx)
    }
}

@Composable
fun ThemeCard(vm: MainVM, s: AppSettings) {
    SectionCard("Theme") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gaming" to "Neon Gaming", "classic" to "Classic Purple").forEach { t ->
                FilterChip(selected = s.theme == t.first, onClick = { vm.saveSettings(s.copy(theme = t.first)) }, label = { Text(t.second) })
            }
        }
    }
}

@Composable
fun CallsCard(vm: MainVM, s: AppSettings) {
    var alias by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    SectionCard("Calls and contacts") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask before the first call to someone")
                Text("After that, calls to the same person go through at once.", color = Muted, fontSize = 12.sp)
            }
            Switch(s.confirmFirstCall, { vm.saveSettings(s.copy(confirmFirstCall = it)) })
        }
        Text("Nicknames. You can also say \"momsy is mom\" to teach one, and I learn them when a call works.", color = Muted, fontSize = 12.sp)
        if (vm.nicknames.isEmpty()) Text("None yet.", color = Muted, fontSize = 13.sp)
        vm.nicknames.entries.toList().forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.key + "  →  " + e.value, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ vm.removeNickname(e.key) }) { Text("Delete", color = Bad, fontSize = 12.sp) }
            }
        }
        OutlinedTextField(alias, { alias = it }, label = { Text("Nickname, e.g. momsy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(target, { target = it }, label = { Text("Saved contact name, e.g. Mom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            { if (alias.isNotBlank() && target.isNotBlank()) { vm.addNickname(alias, target); alias = ""; target = "" } },
            Modifier.fillMaxWidth()
        ) { Text("Add nickname") }
    }
}

@Composable
fun MusicCard(vm: MainVM, s: AppSettings) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    val apps = remember(pick) { Actions.musicApps(ctx) }
    val current = apps.firstOrNull { it.first == s.musicApp }?.second
        ?: if (s.musicApp.isBlank()) "Let Android choose" else s.musicApp
    SectionCard("Preferred apps") {
        Text("Music app: $current")
        OutlinedButton({ pick = true }, Modifier.fillMaxWidth()) { Text("Choose music app") }
    }
    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text("Music app") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Let Android choose",
                        Modifier.fillMaxWidth().clickable { vm.saveSettings(s.copy(musicApp = "")); pick = false }.padding(vertical = 12.dp),
                        color = if (s.musicApp.isBlank()) MaterialTheme.colorScheme.primary else Color.White
                    )
                    apps.forEach { a ->
                        Text(
                            a.second,
                            Modifier.fillMaxWidth().clickable { vm.saveSettings(s.copy(musicApp = a.first)); pick = false }.padding(vertical = 12.dp),
                            color = if (a.first == s.musicApp) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    if (apps.isEmpty()) Text("No music app that can play songs by name was found.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ pick = false }) { Text("Close") } }
        )
    }
}

@Composable
fun KeepAliveCard() {
    val ctx = LocalContext.current
    SectionCard(PhoneGuide.title()) {
        Text(PhoneGuide.steps(), color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ openBatterySettings(ctx) }, Modifier.weight(1f)) { Text("Battery") }
            OutlinedButton({ if (!PhoneGuide.openAutostart(ctx)) openAppInfo(ctx) }, Modifier.weight(1f)) { Text("Autostart") }
        }
    }
}

@Composable
fun MemoryCard(vm: MainVM) {
    var newFact by remember { mutableStateOf("") }
    SectionCard("Memory") {
        Text(
            "What DJ and SHUBHU remember about you. Say \"remember that…\" to add something. It is stored only on this phone and sent to Gemini with your messages.",
            color = Muted, fontSize = 12.sp
        )
        if (vm.facts.isEmpty()) Text("Nothing yet.", color = Muted, fontSize = 13.sp)
        vm.facts.toList().forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ vm.removeFact(f) }) { Text("Delete", color = Bad, fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newFact, { newFact = it }, label = { Text("Add something") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton({ if (newFact.isNotBlank()) { vm.addFact(newFact); newFact = "" } }) { Text("Add") }
        }
        if (vm.facts.isNotEmpty()) OutlinedButton({ vm.clearFacts() }, Modifier.fillMaxWidth()) { Text("Forget everything") }
    }
}

@Composable
fun BlockedCard(vm: MainVM) {
    var name by remember { mutableStateOf("") }
    SectionCard("Blocked apps (agent never reads these)") {
        Text(
            "Banking, payment and password apps are always blocked. Add any other app name or part of its package name here.",
            color = Muted, fontSize = 12.sp
        )
        vm.blocklist.toList().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ vm.removeBlocked(b) }) { Text("Remove", color = Bad, fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("e.g. whatsapp") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton({ if (name.isNotBlank()) { vm.addBlocked(name); name = "" } }) { Text("Block") }
        }
    }
}

@Composable
fun TasksCard(vm: MainVM) {
    var saving by remember { mutableStateOf<TaskEntry?>(null) }
    SectionCard("Task log") {
        Text("What agent mode did lately. Run a task again, or save it as a one-phrase command.", color = Muted, fontSize = 12.sp)
        if (vm.tasks.isEmpty()) Text("No tasks yet.", color = Muted, fontSize = 13.sp)
        vm.tasks.toList().takeLast(8).reversed().forEach { t ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(t.ok)
                    Spacer(Modifier.width(8.dp))
                    Text(t.goal, Modifier.weight(1f), fontSize = 13.sp)
                }
                Text(t.result, color = Muted, fontSize = 12.sp)
                Row {
                    TextButton({ vm.runTask(t.goal) }) { Text("Run again", fontSize = 12.sp) }
                    TextButton({ saving = t }) { Text("Save as command", fontSize = 12.sp) }
                }
            }
        }
        if (vm.tasks.isNotEmpty()) OutlinedButton({ vm.clearTasks() }, Modifier.fillMaxWidth()) { Text("Clear log") }
    }
    saving?.let { t ->
        var phrase by remember(t) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = null },
            title = { Text("Save as command") },
            text = { OutlinedTextField(phrase, { phrase = it }, label = { Text("When I say…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(
                    enabled = phrase.trim().length >= 3,
                    onClick = { vm.addCustom(CustomCmd(phrase.trim(), "task", t.goal)); saving = null }
                ) { Text("Save") }
            },
            dismissButton = { TextButton({ saving = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun BackupCard(vm: MainVM) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            vm.backupMsg = vm.importBackup(text)
        }
    }
    SectionCard("Backup and restore") {
        Text(
            "Saves your settings, commands, nicknames, memory and blocked apps. Your API key is never included.",
            color = Muted, fontSize = 12.sp
        )
        OutlinedButton({
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Shubhix AI backup")
                .putExtra(Intent.EXTRA_TEXT, vm.exportBackup())
            ctx.startActivity(Intent.createChooser(send, "Save backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, Modifier.fillMaxWidth()) { Text("Back up (share a file)") }
        OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Restore from a backup file") }
        if (vm.backupMsg.isNotEmpty()) Text(vm.backupMsg, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun ToolsCard(vm: MainVM) {
    val ctx = LocalContext.current
    var showTest by remember { mutableStateOf(false) }
    SectionCard("Tools") {
        OutlinedButton({ vm.runSelfTest(); showTest = true }, Modifier.fillMaxWidth()) { Text("Run command test") }
        OutlinedButton({ vm.checkUpdate() }, Modifier.fillMaxWidth()) { Text("Check for updates") }
        if (vm.updateMsg.isNotEmpty()) Text(vm.updateMsg, color = Muted, fontSize = 13.sp)
        if (vm.updateNewer) {
            Button({
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.fillMaxWidth()) { Text("Download update") }
        }
    }
    if (showTest) {
        AlertDialog(
            onDismissRequest = { showTest = false },
            title = { Text("Command test") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (vm.selfTest.isEmpty()) Text("Testing…", color = Muted)
                    vm.selfTest.forEach { r ->
                        Row(verticalAlignment = Alignment.Top) {
                            StatusDot(r.ok)
                            Spacer(Modifier.width(8.dp))
                            Text(r.label + ": " + r.detail, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showTest = false }) { Text("Close") } }
        )
    }
}
