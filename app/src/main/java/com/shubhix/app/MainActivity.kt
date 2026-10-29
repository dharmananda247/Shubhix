package com.shubhix.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS))
        setContent { ShubhixTheme { App(vm) } }
    }
}

@Composable
fun App(vm: MainVM) {
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf("Chat" to Icons.Default.Home, "Commands" to Icons.Default.List, "Settings" to Icons.Default.Settings)
    Scaffold(
        containerColor = Charcoal,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = Blue,
                            indicatorColor = Blue, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> CommandsScreen()
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun ChatScreen(vm: MainVM) {
    val s by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.thinking) {
        val n = vm.messages.size + if (vm.thinking) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shubhix", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            listOf("DJ", "SHUBHU").forEach { p ->
                FilterChip(
                    selected = s.persona == p, onClick = { vm.setPersona(p) },
                    label = { Text(p) }, modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty()) item {
                Text(
                    if (s.persona == "DJ") "Hey, main DJ hoon. Bol, kya karna hai?" else "Hii, main SHUBHU hoon. Bolo, kya karu?",
                    color = Muted, modifier = Modifier.padding(24.dp)
                )
            }
            items(vm.messages) { Bubble(it) }
            if (vm.thinking) item { Text("typing…", color = Muted, modifier = Modifier.padding(8.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Orb(vm.listening) { vm.mic() }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Type karo ya mic dabao") },
                shape = RoundedCornerShape(24.dp), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" })
            )
            IconButton(onClick = { vm.send(input); input = "" }) {
                Icon(Icons.Default.Send, "Send", tint = Blue)
            }
        }
    }
}

@Composable
fun Orb(listening: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val sc by t.animateFloat(
        1f, if (listening) 1.2f else 1.06f,
        infiniteRepeatable(tween(if (listening) 600 else 1800), RepeatMode.Reverse), label = "s"
    )
    Box(
        Modifier.size(56.dp).scale(sc).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Blue, Blue.copy(alpha = 0.35f))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(if (listening) "●" else "🎤", fontSize = 22.sp) }
}

@Composable
fun Bubble(m: ChatMsg) {
    val me = m.role == "user"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (me) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (me) Blue else Panel).padding(12.dp)
        ) {
            m.text.split("```").forEachIndexed { i, part ->
                if (i % 2 == 0) {
                    if (part.isNotBlank()) Text(part.trim(), color = Color.White)
                } else {
                    CodeBlock(part.substringAfter("\n", part).trim())
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    val clip = LocalClipboardManager.current
    Column(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F0F11)).padding(10.dp)) {
        Text(code, color = Color(0xFF93C5FD), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()))
        TextButton(onClick = { clip.setText(AnnotatedString(code)) }) { Text("Copy", color = Blue) }
    }
}

@Composable
fun CommandsScreen() {
    val cmds = listOf(
        "WhatsApp kholo", "Camera open karo", "Arijit Singh ka gana chalao", "Gaana pause karo / next karo",
        "Neeche scroll karo", "Back jao / Home jao", "Send button dabao", "Search box me hello likho",
        "Mummy ko call karo", "Torch on karo", "10 minute ka timer lagao", "Kal subah 6 baje alarm lagao",
        "Volume badhao", "Python me calculator ka code do", "Is code ko explain karo", "Google pe weather search karo"
    )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Sample commands", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp)) }
        items(cmds) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Text(it, Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainVM) {
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    var api by remember(s) { mutableStateOf(s.apiKey) }
    var model by remember(s) { mutableStateOf(s.model) }
    var speed by remember(s) { mutableFloatStateOf(s.speed) }
    var dj by remember(s) { mutableStateOf(s.djPrompt) }
    var shubhu by remember(s) { mutableStateOf(s.shubhuPrompt) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(api, { api = it }, label = { Text("Gemini API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Gemini model (e.g. gemini-2.5-flash)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Voice speed: ${"%.1f".format(speed)}")
        Slider(speed, { speed = it }, valueRange = 0.6f..1.6f)
        OutlinedTextField(dj, { dj = it }, label = { Text("DJ prompt") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(shubhu, { shubhu = it }, label = { Text("SHUBHU prompt") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.saveSettings(s.copy(apiKey = api, model = model, speed = speed, djPrompt = dj, shubhuPrompt = shubhu)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Screen control ON karo (Accessibility)") }
        Text(
            "Accessibility sirf tab use hoti hai jab aap tap/scroll/type bolte ho. Android ise auto-on nahi hone deta, " +
                "Installed apps me Shubhix chunke ON karo.", color = Muted, fontSize = 12.sp
        )
        OutlinedButton(onClick = { vm.messages.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Chat clear karo") }
    }
}
