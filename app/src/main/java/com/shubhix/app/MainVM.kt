package com.shubhix.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException

class MainVM(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val voice = Voice(app)
    private val history = HistoryStore(app)
    private val customStore = CustomStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // Initial onboarded=true avoids flashing the "choose companion" screen before settings load.
    val settings = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings(onboarded = true))
    val messages = mutableStateListOf<ChatMsg>()
    val custom = mutableStateListOf<CustomCmd>()
    var thinking by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var online by mutableStateOf(true)

    // Settings screen status
    var testOk by mutableStateOf<Boolean?>(null)
    var testMsg by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var modelsMsg by mutableStateOf("")
    var accTest by mutableStateOf("")
    var crash by mutableStateOf(CrashLog.read(app))

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // Callbacks arrive on a background thread, so hop to the main thread before touching UI state.
        override fun onAvailable(network: Network) { main.post { online = true } }
        override fun onLost(network: Network) { main.post { online = checkOnline() } }
    }

    init {
        runCatching { online = checkOnline() }
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        runCatching { custom.addAll(customStore.load()) }
        viewModelScope.launch {
            runCatching {
                val s = prefs.flow.first()
                messages.addAll(history.load(s.retentionDays)) // chats past the retention limit are dropped here
                history.save(messages.toList())
            }
        }
    }

    private fun checkOnline(): Boolean = try {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        true
    }

    private fun add(m: ChatMsg) {
        messages += m
        history.save(messages.toList())
    }

    private fun speak(text: String, s: AppSettings) {
        if (s.voiceReply) voice.speak(text, s.persona, s.speed, s.voiceName(), s.pitch())
    }

    // ---- settings ----
    fun setPersona(p: String) { viewModelScope.launch { prefs.save(settings.value.copy(persona = p)) } }
    fun finishOnboarding(p: String) { viewModelScope.launch { prefs.save(settings.value.copy(persona = p, onboarded = true)) } }

    fun setVoiceReply(on: Boolean) {
        if (!on) voice.stopSpeaking()
        saveSettings(settings.value.copy(voiceReply = on))
    }

    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            prefs.save(s)
            if (s.retentionDays > 0) {
                val cutoff = System.currentTimeMillis() - s.retentionDays * 86_400_000L
                if (messages.removeAll { it.time < cutoff }) history.save(messages.toList())
            }
        }
    }

    fun clearChat() { messages.clear(); history.save(emptyList()) }
    fun clearCrash() { CrashLog.clear(getApplication()); crash = "" }

    fun voices() = voice.voices()
    fun preview(persona: String, voiceName: String, pitch: Float) =
        voice.speak("Hi, this is how I sound.", persona, settings.value.speed, voiceName, pitch)

    // ---- custom commands ----
    fun addCustom(c: CustomCmd) { custom.add(c); customStore.save(custom.toList()) }
    fun removeCustom(c: CustomCmd) { custom.remove(c); customStore.save(custom.toList()) }

    // ---- Gemini key test and model list ----
    fun saveAndTest(s: AppSettings) {
        saveSettings(s)
        testOk = null
        testMsg = "Testing…"
        viewModelScope.launch {
            try {
                withTimeout(20_000) { GeminiClient.test(s.apiKey, s.model) }
                testOk = true
                testMsg = "Connected ✓ · ${s.model}"
            } catch (e: TimeoutCancellationException) {
                testOk = false; testMsg = "Timed out after 20 seconds. Check your internet."
            } catch (e: UnknownHostException) {
                testOk = false; testMsg = "No internet connection."
            } catch (e: IOException) {
                testOk = false; testMsg = "Network problem: ${e.message}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                testOk = false; testMsg = e.message ?: "Unknown error"
            }
        }
    }

    suspend fun loadModels(key: String) {
        modelsMsg = "Loading…"
        try {
            models = withTimeout(20_000) { GeminiClient.listModels(key) }
            modelsMsg = if (models.isEmpty()) "No models found for this key." else ""
        } catch (e: TimeoutCancellationException) {
            modelsMsg = "Timed out. Check your internet."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelsMsg = e.message ?: "Could not load models."
        }
    }

    fun testScreenControl() {
        viewModelScope.launch {
            val note = Actions.runAll(
                getApplication<Application>(),
                listOf(JSONObject().put("type", "system").put("name", "notifications"))
            )
            accTest = note ?: "Working ✓ The notification panel should have opened."
        }
    }

    // ---- voice input and chat ----
    fun mic() {
        if (listening) return
        listening = true
        voice.listen(
            settings.value.listenLang,
            onText = { listening = false; send(it) },
            onEnd = { listening = false }
        )
    }

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        val s = settings.value
        val app = getApplication<Application>()
        add(ChatMsg("user", text.trim(), persona = s.persona))

        // 1) Phone commands and custom shortcuts run locally: instant, no internet needed.
        val local = LocalCommands.parse(app, text, custom.toList())
        if (local != null && local.askAi == null) {
            viewModelScope.launch {
                var reply = local.reply
                val note = Actions.runAll(app, local.actions)
                speak(reply, s)
                if (note != null) reply += "\n\n($note)"
                add(ChatMsg("model", reply, persona = s.persona))
            }
            return
        }

        // 2) Everything else needs Gemini, which needs internet.
        if (!online) {
            add(ChatMsg("model", OFFLINE_MSG, persona = s.persona))
            return
        }
        val aiPrompt = local?.askAi
        thinking = true
        viewModelScope.launch {
            var reply: String
            try {
                val apps = if (s.shareApps) withContext(Dispatchers.Default) { Actions.installedApps(app) } else emptyList()
                val system = Brain.buildSystem(s.prompt(), apps, custom.toList())
                val hist = messages.toList().let { if (aiPrompt != null) it.dropLast(1) + ChatMsg("user", aiPrompt) else it }
                val r = withTimeout(20_000) { GeminiClient.ask(s.apiKey, s.model, system, hist) }
                reply = r.reply.ifBlank { "Done." }
                val note = Actions.runAll(app, r.actions)
                speak(reply, s)
                if (note != null) reply += "\n\n($note)"
            } catch (e: TimeoutCancellationException) {
                reply = "That took longer than 20 seconds, so I stopped. Check your connection and try again."
            } catch (e: IOException) {
                reply = OFFLINE_MSG
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply = "Error: ${e.message}"
            }
            thinking = false
            add(ChatMsg("model", reply, persona = s.persona))
        }
    }

    override fun onCleared() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        voice.release()
    }

    companion object {
        const val OFFLINE_MSG = "No internet right now. Simple commands like \"open WhatsApp\", \"torch on\", " +
            "\"volume up\" or \"what time is it\" still work offline. Chat and coding need a connection."
    }
}
