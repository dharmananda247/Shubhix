package com.shubhix.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(vm: MainVM) {
    val s by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val send = { if (input.isNotBlank()) { vm.send(input); input = "" } }

    LaunchedEffect(vm.messages.size, vm.thinking) {
        val n = vm.messages.size + if (vm.thinking) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(vm.online, s.persona) { vm.setPersona(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.messages.isEmpty() && !vm.thinking) {
                EmptyState(s.persona) { vm.send(it) }
            } else {
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vm.messages) { Bubble(it, s.persona) }
                    if (vm.thinking) item { Thinking(s.persona) }
                }
            }
        }
        if (vm.listening) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Waveform(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Listening…", color = Muted, fontSize = 13.sp)
            }
        }
        InputBar(input, { input = it }, vm.listening, send) { vm.mic() }
    }
}

@Composable
private fun Header(online: Boolean, persona: String, onPersona: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_head), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Shubhix", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(online, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (online) "Online" else "Offline mode", color = Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        PersonaSwitch(persona, onPersona)
    }
}

@Composable
private fun PersonaSwitch(selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.clip(shape).background(Panel).border(1.dp, BorderC, shape).padding(4.dp)) {
        listOf("DJ", "SHUBHU").forEach { p ->
            val sel = p == selected
            Row(
                Modifier.clip(RoundedCornerShape(24.dp))
                    .background(if (sel) accentFor(p).copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(p, 26.dp, ring = sel)
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(p, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(persona: String, onTry: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GlowAvatar(persona, 120.dp)
        Spacer(Modifier.height(24.dp))
        Text(if (persona == "DJ") "Hey, I'm DJ." else "Hi, I'm SHUBHU.", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Say it or type it. I'll handle the rest.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        listOf(
            listOf("Open WhatsApp", "Torch on"),
            listOf("Play Arijit Singh songs", "Write a Python calculator")
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { QuickChip(it, onTry) } }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun QuickChip(text: String, onClick: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape).background(Panel).border(1.dp, BorderC, shape)
            .clickable { onClick(text) }.padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun Thinking(persona: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(persona, 28.dp)
        Spacer(Modifier.width(8.dp))
        Waveform(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    }
}

@Composable
private fun Bubble(m: ChatMsg, fallbackPersona: String) {
    val me = m.role == "user"
    val p = m.persona.ifEmpty { fallbackPersona }
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (me) 18.dp else 4.dp, bottomEnd = if (me) 4.dp else 18.dp
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (me) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!me) {
            Avatar(p, 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.widthIn(max = 290.dp).clip(shape)
                .background(if (me) accent.copy(alpha = 0.28f) else Panel)
                .border(1.dp, if (me) accent.copy(alpha = 0.6f) else BorderC, shape)
                .padding(12.dp)
        ) {
            m.text.split("```").forEachIndexed { i, part ->
                if (i % 2 == 0) {
                    if (part.isNotBlank()) Text(part.trim(), color = Color.White, fontSize = 15.sp)
                } else {
                    val first = part.substringBefore("\n", "").trim()
                    val hasLang = part.contains("\n") && first.length <= 15 && !first.contains(' ')
                    CodeBlock(if (hasLang) first else "", (if (hasLang) part.substringAfter("\n") else part).trim())
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clip = LocalClipboardManager.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(shape)
            .background(Color(0xFF0B0714)).border(1.dp, BorderC, shape)
    ) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.uppercase().ifEmpty { "CODE" }, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { clip.setText(AnnotatedString(code)) }) { Text("Copy", color = Glow, fontSize = 12.sp) }
        }
        Text(
            code, color = Color(0xFFE9D5FF), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
        )
    }
}

@Composable
private fun InputBar(
    input: String, onInput: (String) -> Unit, listening: Boolean,
    onSend: () -> Unit, onMic: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(shape).background(Panel)
            .border(1.dp, BorderC, shape).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = input, onValueChange = onInput, modifier = Modifier.weight(1f),
            placeholder = { Text("Message…", color = Muted) }, maxLines = 3,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent, cursorColor = accent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        if (input.isNotBlank()) {
            IconButton(onClick = onSend) { Icon(Icons.Default.Send, "Send", tint = accent) }
        } else {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.45f))))
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center
            ) { Text(if (listening) "●" else "🎤", fontSize = 20.sp) }
        }
    }
}
