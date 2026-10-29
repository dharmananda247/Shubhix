package com.shubhix.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    var api by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var show by remember { mutableStateOf(false) }
    var speed by remember(s.speed) { mutableFloatStateOf(s.speed) }
    var editing by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val micOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val contactsOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    val accOk = remember(tick) { ShubhixAccessibilityService.instance != null }

    val appSettings = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
    val accSettings = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SectionCard("AI Brain") {
            OutlinedTextField(
                api, { api = it }, label = { Text("Gemini API key") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", fontSize = 12.sp) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(s.apiKey.isNotBlank())
                Spacer(Modifier.width(8.dp))
                Text(if (s.apiKey.isNotBlank()) "Key saved" else "No key yet", color = Muted, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ vm.saveSettings(s.copy(apiKey = api.trim(), model = model.trim())) }, Modifier.weight(1f)) { Text("Save") }
                OutlinedButton({ vm.testConnection(api.trim(), model.trim()) }, Modifier.weight(1f)) { Text("Test") }
            }
            if (vm.testResult.isNotEmpty()) Text(vm.testResult, color = Muted, fontSize = 13.sp)
        }

        SectionCard("Personas") {
            listOf("DJ", "SHUBHU").forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 44.dp, ring = s.persona == p)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p, fontWeight = FontWeight.SemiBold)
                        Text(if (s.persona == p) "Active" else "Tap Use to switch", color = Muted, fontSize = 12.sp)
                    }
                    if (s.persona != p) TextButton({ vm.setPersona(p) }) { Text("Use") }
                    TextButton({ editing = p }) { Text("Edit prompt") }
                }
            }
            Text("Voice speed: ${"%.1f".format(speed)}x", color = Muted, fontSize = 13.sp)
            Slider(speed, { speed = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = { vm.saveSettings(s.copy(speed = speed)) })
        }

        SectionCard("Permissions") {
            PermRow("Microphone", micOk, appSettings)
            PermRow("Contacts (for calls)", contactsOk, appSettings)
            PermRow("Screen control (Accessibility)", accOk, accSettings)
            Text(
                "Screen control is used only when you ask Shubhix to tap, scroll or type. Android never turns it on automatically.",
                color = Muted, fontSize = 12.sp
            )
        }

        SectionCard("Chat history") {
            Text("Delete chats older than", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Never", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                    FilterChip(selected = s.retentionDays == d, onClick = { vm.saveSettings(s.copy(retentionDays = d)) }, label = { Text(label) })
                }
            }
            OutlinedButton({ vm.clearChat() }, Modifier.fillMaxWidth()) { Text("Clear chat now") }
            Text("Chats are stored only on this phone.", color = Muted, fontSize = 12.sp)
        }

        SectionCard("About") { Text("Shubhix v1.2", color = Muted) }
        Spacer(Modifier.padding(8.dp))
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
private fun PermRow(name: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Text(name, modifier = Modifier.weight(1f))
        if (ok) Text("On", color = Muted, fontSize = 13.sp) else TextButton(onFix) { Text("Enable") }
    }
}
