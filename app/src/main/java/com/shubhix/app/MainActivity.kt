package com.shubhix.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS))
        setContent {
            val st by vm.settings.collectAsState()
            ShubhixTheme(accent = accentFor(st.persona)) {
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1500); splash = false }
                when {
                    splash -> Splash()
                    !st.onboarded -> Onboarding { vm.finishOnboarding(it) }
                    else -> App(vm)
                }
            }
        }
    }
}

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.logo_full), "Shubhix",
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
        listOf("DJ" to "Confident, caring, fun", "SHUBHU" to "Sweet, caring, playful").forEach { (p, tag) ->
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
                Text(tag, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
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
                    items.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) },
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
                0 -> ChatScreen(vm)
                1 -> CommandsScreen { tab = 0; vm.send(it) }
                else -> SettingsScreen(vm)
            }
        }
    }
}
