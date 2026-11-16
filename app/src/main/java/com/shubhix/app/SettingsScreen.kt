package com.shubhix.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun SettingsScreen(vm: MainVM) {
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    var api by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var show by remember { mutableStateOf(false) }
    var speed by remember(s.speed) { mutableFloatStateOf(s.speed) }
    var djPitch by remember(s.djPitch) { mutableFloatStateOf(s.djPitch) }
    var shubhuPitch by remember(s.shubhuPitch) { mutableFloatStateOf(s.shubhuPitch) }
    var editing by remember { mutableStateOf<String?>(null) }
    var voicePick by remember { mutableStateOf<String?>(null) }
    var pickModel by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val micOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val contactsOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    val callOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED }
    val accRunning = remember(tick) { ShubhixAccessibilityService.instance != null }
    val accEnabled = remember(tick) { ShubhixAccessibilityService.isEnabled(ctx) }
    val overlayOk = remember(tick) { BubbleService.canDraw(ctx) }
    val notifOk = remember(tick) { NotificationStore.hasAccess(ctx) }

    val appSettings = { openAppInfo(ctx) }
    val accSettings = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        ThemeCard(vm, s)

        SectionCard("AI Brain") {
            OutlinedTextField(
                api, { api = it }, label = { Text("Gemini API key") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", fontSize = 12.sp) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(vm.testOk == true, color = if (vm.testOk == null) Muted else null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        vm.testMsg.isNotEmpty() -> vm.testMsg
                        s.apiKey.isNotBlank() -> "Key saved. Tap Save & test to check it."
                        else -> "No key yet"
                    },
                    color = when (vm.testOk) { true -> Good; false -> Bad; null -> Muted },
                    fontSize = 13.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ vm.saveAndTest(s.copy(apiKey = api.trim(), model = model.trim())) }, Modifier.weight(1f)) { Text("Save & test") }
                OutlinedButton({ pickModel = true }, Modifier.weight(1f)) { Text("Choose model") }
            }
            OutlinedButton({ vm.runDiagnostics(api.trim(), model.trim()); showDiag = true }, Modifier.fillMaxWidth()) { Text("Connection check") }
            Text("If your chosen model stops working, Shubhix AI quietly switches to one that does.", color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let the AI see my app names")
                    Text("Helps it open the right app. Only names are sent, nothing else.", color = Muted, fontSize = 12.sp)
                }
                Switch(s.shareApps, { vm.saveSettings(s.copy(shareApps = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Multi-step phone control")
                    Text(
                        "Lets the AI look at the screen and finish tasks across apps. While a task runs, the on-screen text of that app is sent to Gemini. Password fields, banking and payment apps are never read.",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Switch(s.agentOn, { vm.saveSettings(s.copy(agentOn = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Look at a screenshot when needed")
                    Text("Only if an app's screen cannot be read, and only during a task. Needs Android 11 or newer.", color = Muted, fontSize = 12.sp)
                }
                Switch(s.visionOn, { vm.saveSettings(s.copy(visionOn = it)) })
            }
        }

        SectionCard("Voice and personas") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Voice replies")
                    Text("Speak answers out loud", color = Muted, fontSize = 12.sp)
                }
                Switch(s.voiceReply, { vm.setVoiceReply(it) })
            }
            Text("Listening language", color = Muted, fontSize = 13.sp)
            Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("hi-IN" to "Hindi + English", "en-IN" to "English", "or-IN" to "Odia", "bn-IN" to "Bengali").forEach { l ->
                    FilterChip(selected = s.listenLang == l.first, onClick = { vm.saveSettings(s.copy(listenLang = l.first)) }, label = { Text(l.second) })
                }
            }
            Text("Voice speed: ${"%.1f".format(speed)}x", color = Muted, fontSize = 13.sp)
            Slider(speed, { speed = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = { vm.saveSettings(s.copy(speed = speed)) })

            listOf("DJ", "SHUBHU").forEach { p ->
                val pitch = if (p == "DJ") djPitch else shubhuPitch
                val voiceName = if (p == "DJ") s.djVoice else s.shubhuVoice
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 44.dp, ring = s.persona == p)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p, fontWeight = FontWeight.SemiBold)
                        Text(if (s.persona == p) "Active" else "Not active", color = Muted, fontSize = 12.sp)
                    }
                    if (s.persona != p) TextButton({ vm.setPersona(p) }) { Text("Use") }
                    TextButton({ editing = p }) { Text("Edit prompt") }
                }
                Text("$p tone (pitch): ${"%.2f".format(pitch)}  ·  lower is deeper", color = Muted, fontSize = 13.sp)
                Slider(
                    pitch,
                    { if (p == "DJ") djPitch = it else shubhuPitch = it },
                    valueRange = 0.5f..1.6f,
                    onValueChangeFinished = {
                        vm.saveSettings(if (p == "DJ") s.copy(djPitch = djPitch) else s.copy(shubhuPitch = shubhuPitch))
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ voicePick = p }, Modifier.weight(1f)) {
                        Text(if (voiceName.isEmpty()) "Voice: Default" else "Voice: change")
                    }
                    TextButton({ vm.preview(p, voiceName, pitch) }) { Text("▶ Preview") }
                }
            }
        }

        CallsCard(vm, s)
        MusicCard(vm, s)

        SectionCard("Floating bubble") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show the bubble over other apps")
                    Text(
                        "Tap to talk from any app. Drag to move, long-press to open Shubhix AI, tap while a task runs to stop it.",
                        color = Muted, fontSize = 12.sp
                    )
                }
                Switch(s.bubbleOn, { on ->
                    vm.saveSettings(s.copy(bubbleOn = on))
                    if (!on) BubbleService.stop(ctx)
                    else if (!BubbleService.canDraw(ctx)) openOverlaySettings(ctx)
                    else if (!micOk) appSettings()
                    else BubbleService.start(ctx)
                })
            }
            Text("Tip: add the Talk to Shubhix AI tile or the home-screen widget for one-tap talking.", color = Muted, fontSize = 12.sp)
        }

        SectionCard("Permissions") {
            PermRow("Microphone", micOk, null, appSettings)
            PermRow("Contacts (for calls)", contactsOk, null, appSettings)
            PermRow("Phone calls (dial automatically)", callOk, null, appSettings)
            PermRow("Display over other apps", overlayOk, null) { openOverlaySettings(ctx) }
            PermRow("Notification access (read aloud)", notifOk, null) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow(
                "Screen control (Accessibility)", accRunning,
                when {
                    accRunning -> null
                    accEnabled -> "Turned on but not running. Switch it off and on again."
                    else -> "Off. Tap Enable, open Installed apps, choose Shubhix AI and turn it on."
                },
                accSettings
            )
            OutlinedButton({ vm.testScreenControl() }, Modifier.fillMaxWidth()) { Text("Test screen control") }
            if (vm.accTest.isNotEmpty()) Text(vm.accTest, color = Muted, fontSize = 13.sp)
            Text(
                "Screen control acts on the app that is open on screen. Android never turns it on automatically.",
                color = Muted, fontSize = 12.sp
            )
        }

        KeepAliveCard()

        SectionCard("Chat history") {
            Text("Delete chats older than", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Never", 7 to "7 days", 30 to "30 days").forEach { d ->
                    FilterChip(selected = s.retentionDays == d.first, onClick = { vm.saveSettings(s.copy(retentionDays = d.first)) }, label = { Text(d.second) })
                }
            }
            OutlinedButton({
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.exportText())
                ctx.startActivity(Intent.createChooser(send, "Share chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.fillMaxWidth()) { Text("Export chat") }
            OutlinedButton({ vm.clearChat() }, Modifier.fillMaxWidth()) { Text("Clear chat now") }
            Text("Chats are stored only on this phone.", color = Muted, fontSize = 12.sp)
        }

        MemoryCard(vm)
        BlockedCard(vm)
        TasksCard(vm)
        BackupCard(vm)
        ToolsCard(vm)

        SectionCard("About") {
            Text("Shubhix AI v2.1", color = Muted)
            if (vm.crash.isNotEmpty()) {
                Text("The app closed unexpectedly last time. Copy this and send it to be fixed:", color = Bad, fontSize = 12.sp)
                Text(vm.crash.take(700), color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Shubhix crash", vm.crash))
                    }) { Text("Copy") }
                    OutlinedButton({ vm.clearCrash() }) { Text("Clear") }
                }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }

    // ---- dialogs ----
    if (showDiag) {
        AlertDialog(
            onDismissRequest = { showDiag = false },
            title = { Text("Connection check") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.diagRunning) Text("Checking…", color = Muted)
                    vm.diag.forEach { c ->
                        Row(verticalAlignment = Alignment.Top) {
                            StatusDot(c.ok)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(c.detail, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showDiag = false }) { Text("Close") } }
        )
    }

    if (pickModel) {
        LaunchedEffect(Unit) { vm.loadModels(api.trim()) }
        AlertDialog(
            onDismissRequest = { pickModel = false },
            title = { Text("Choose model") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (vm.modelsMsg.isNotEmpty()) Text(vm.modelsMsg, color = Muted, fontSize = 13.sp)
                    vm.models.forEach { m ->
                        Text(
                            m,
                            Modifier.fillMaxWidth().clickable {
                                model = m
                                vm.saveSettings(s.copy(apiKey = api.trim(), model = m))
                                pickModel = false
                            }.padding(vertical = 12.dp),
                            color = if (m == model) accent else Color.White
                        )
                    }
                }
            },
            confirmButton = { TextButton({ pickModel = false }) { Text("Close") } }
        )
    }

    voicePick?.let { p ->
        val list = remember(p) { vm.voices() }
        val cur = if (p == "DJ") s.djVoice else s.shubhuVoice
        val pitch = if (p == "DJ") djPitch else shubhuPitch
        val pick = { name: String -> vm.saveSettings(if (p == "DJ") s.copy(djVoice = name) else s.copy(shubhuVoice = name)) }
        AlertDialog(
            onDismissRequest = { voicePick = null },
            title = { Text("$p voice") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    VoiceRow("Default voice", cur.isEmpty(), { pick("") }, { vm.preview(p, "", pitch) })
                    if (list.isEmpty()) {
                        Text("No other voices found. Add voices in Android Settings > Text-to-speech.", color = Muted, fontSize = 12.sp)
                    }
                    list.forEach { v -> VoiceRow(v.second, v.first == cur, { pick(v.first) }, { vm.preview(p, v.first, pitch) }) }
                }
            },
            confirmButton = { TextButton({ voicePick = null }) { Text("Done") } }
        )
    }

    editing?.let { p ->
        var text by remember(p) { mutableStateOf(if (p == "DJ") s.djPrompt else s.shubhuPrompt) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("$p prompt") },
            text = { OutlinedTextField(text, { text = it }, minLines = 6, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton({
                    vm.saveSettings(if (p == "DJ") s.copy(djPrompt = text) else s.copy(shubhuPrompt = text))
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun VoiceRow(label: String, selected: Boolean, onPick: () -> Unit, onPreview: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else Color.White, fontSize = 13.sp)
        TextButton(onPreview) { Text("▶") }
    }
}

@Composable
private fun PermRow(name: String, ok: Boolean, hint: String?, onFix: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok)
            Spacer(Modifier.width(10.dp))
            Text(name, modifier = Modifier.weight(1f))
            if (ok) Text("On", color = Muted, fontSize = 13.sp) else TextButton(onFix) { Text("Enable") }
        }
        if (hint != null) Text(hint, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp))
    }
}
