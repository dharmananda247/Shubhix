package com.shubhix.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainVM by lazy { (application as ShubhixApp).vm }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    // Google's own voice box, used when the built-in recogniser keeps failing.
    private val voicePopup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        vm.onVoicePopupResult(heard)
    }

    override fun onStart() {
        super.onStart()
        (application as ShubhixApp).visible = true
    }

    override fun onStop() {
        (application as ShubhixApp).visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.healScreenControl()
        // Bring the floating bubble back if it is switched on and everything it needs is allowed.
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (vm.settings.value.bubbleOn && micOk && BubbleService.canDraw(this) && !BubbleService.running) BubbleService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        vm.launchVoicePopup = null
        super.onDestroy()
    }

    /** From the Quick Settings tile (listen) and the home-screen widget (cmd). */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("listen", false)) {
            i.removeExtra("listen")
            vm.mic()
        }
        val cmd = i.getStringExtra("cmd")
        if (cmd != null) {
            i.removeExtra("cmd")
            when (cmd) {
                "talk" -> vm.mic()
                "camera" -> vm.send("open camera")
                "gallery" -> vm.send("open gallery")
                "whatsapp" -> vm.send("open whatsapp")
            }
        }
    }

    private fun showVoicePopup() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, vm.settings.value.listenLang)
        try {
            voicePopup.launch(i)
        } catch (e: Exception) {
            vm.onVoicePopupResult(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())

        vm.launchVoicePopup = { showVoicePopup() }

        setContent {
            val st by vm.settings.collectAsState()
            SideEffect { Palette.gaming = st.theme == "gaming" }
            ShubhixTheme(accent = accentFor(st.persona)) {
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1500); splash = false }
                when {
                    splash -> Splash()
                    !st.onboarded -> Onboarding { vm.finishOnboarding(it) }
                    else -> App(vm)
                }
                vm.confirmRequest?.let { ConfirmDialog(it) }
            }
        }
        handleIntent(intent)
    }
}

@Composable
fun ConfirmDialog(req: ConfirmRequest) {
    if (req.options.isEmpty()) {
        AlertDialog(
            onDismissRequest = { req.answer.complete(-1) },
            title = { Text("Please confirm") },
            text = { Text(req.question) },
            confirmButton = { TextButton({ req.answer.complete(0) }) { Text("Yes, go ahead") } },
            dismissButton = { TextButton({ req.answer.complete(-1) }) { Text("No") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { req.answer.complete(-1) },
            title = { Text(req.question) },
            text = {
                Column {
                    req.options.forEachIndexed { i, o -> TextButton({ req.answer.complete(i) }) { Text(o) } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ req.answer.complete(-1) }) { Text("Cancel") } }
        )
    }
}

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.logo_full), "Shubhix AI",
            modifier = Modifier.fillMaxWidth(0.8f), contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun Onboarding(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Choose your companion", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("You can switch anytime.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
        listOf("DJ" to "Confident, caring, fun", "SHUBHU" to "Sweet, caring, playful").forEach { pair ->
            val p = pair.first
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Panel)
                    .border(1.dp, accentFor(p).copy(alpha = 0.7f), shape)
                    .clickable { onPick(p) }.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GlowAvatar(p, 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(p, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(pair.second, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun App(vm: MainVM) {
    var tab by remember { mutableIntStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val items = listOf("Chat" to Icons.Default.Home, "Commands" to Icons.Default.List, "Settings" to Icons.Default.Settings)
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderC))
                NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                    items.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(item.second, item.first) }, label = { Text(item.first, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = accent, selectedTextColor = accent,
                                indicatorColor = accent.copy(alpha = 0.15f),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(vm) { tab = 2 }
                1 -> CommandsScreen(vm) { tab = 0; vm.send(it) }
                else -> SettingsScreen(vm)
            }
        }
    }
}
