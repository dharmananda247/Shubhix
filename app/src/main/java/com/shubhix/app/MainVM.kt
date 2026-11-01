package com.shubhix.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val memoryStore = MemoryStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // Initial onboarded=true avoids flashing the "choose companion" screen before settings load.
    val settings = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings(onboarded = true))
    val messages = mutableStateListOf<ChatMsg>()
    val custom = mutableStateListOf<CustomCmd>()
    val facts = mutableStateListOf<String>()
    /** Set by the floating bubble so replies can pop up while another app is open. */
    var echo: ((String) -> Unit)? = null
    var thinking by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var online by mutableStateOf(true)
    var agentRunning by mutableStateOf(false)
    var agentStatus by mutableStateOf("")
    private var micJob: Job? = null

    // Settings screen status
    var testOk by mutableStateOf<Boolean?>(null)
    var testMsg by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var modelsMsg by mutableStateOf("")
    var accTest by mutableStateOf("")
    var crash by mutableStateOf(CrashLog.read(app))

    // Last line of defence: an unexpected error becomes a chat message instead of closing the app.
    private val safety = CoroutineExceptionHandler { _, e ->
        thinking = false
        add(ChatMsg("model", "Something went wrong: ${e.message}", system = true))
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // Callbacks arrive on a background thread, so hop to the main thread before touching UI state.
        override fun onAvailable(network: Network) { main.post { online = true } }
        override fun onLost(network: Network) { main.post { online = checkOnline() } }
    }

    init {
        runCatching { online = checkOnline() }
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        runCatching { custom.addAll(customStore.load()) }
        runCatching { facts.addAll(memoryStore.load()) }
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

    private fun notice(text: String, persona: String = "") = add(ChatMsg("model", text, persona = persona, system = true))

    private fun add(m: ChatMsg) {
        messages += m
        history.save(messages.toList())
        if (m.role == "model") runCatching { echo?.invoke(m.text) }
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

    // ---- memory ----
    fun addFact(fact: String) {
        val f = fact.trim().take(200)
        if (f.length < 3 || facts.any { it.equals(f, ignoreCase = true) }) return
        facts.add(f)
        while (facts.size > 60) facts.removeAt(0)
        memoryStore.save(facts.toList())
    }

    fun removeFact(fact: String) { facts.remove(fact); memoryStore.save(facts.toList()) }
    fun clearFacts() { facts.clear(); memoryStore.save(emptyList()) }

    /** Applies remember/forget actions and returns the remaining actions. */
    private fun handleMemory(actions: List<JSONObject>): List<JSONObject> {
        val rest = mutableListOf<JSONObject>()
        for (a in actions) {
            when (a.optString("type")) {
                "remember" -> addFact(a.optString("fact"))
                "forget" -> {
                    val key = a.optString("fact").trim().lowercase()
                    if (key.length >= 4) {
                        facts.removeAll { it.lowercase().contains(key) }
                        memoryStore.save(facts.toList())
                    }
                }
                else -> rest.add(a)
            }
        }
        return rest
    }

    /** Plain text of the chat, shortcuts and memory, for the Share sheet. */
    fun exportText(): String = buildString {
        append("Shubhix chat export\n\n")
        messages.filter { !it.system }.forEach {
            append(if (it.role == "user") "Me: " else (it.persona.ifEmpty { "Shubhix" } + ": ")).append(it.text).append("\n\n")
        }
        if (custom.isNotEmpty()) {
            append("My commands\n")
            custom.forEach { append("- \"").append(it.phrase).append("\" -> ").append(it.type).append(": ").append(it.value).append("\n") }
            append("\n")
        }
        if (facts.isNotEmpty()) {
            append("Memory\n")
            facts.forEach { append("- ").append(it).append("\n") }
        }
    }

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
        if (listening) { // tapping again stops listening, so the button can never get stuck
            micJob?.cancel()
            voice.cancelListening()
            listening = false
            return
        }
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("Microphone permission is off. Turn it on in Settings > Permissions.")
            return
        }
        listening = true
        micJob = viewModelScope.launch {
            delay(15_000) // safety: if Android never answers, free the button
            if (listening) {
                voice.cancelListening()
                listening = false
                notice("I didn't catch that. Tap the mic and try again.")
            }
        }
        voice.listen(
            settings.value.listenLang, !online,
            onText = { micJob?.cancel(); listening = false; send(it) },
            onEnd = { code ->
                micJob?.cancel()
                listening = false
                micMessage(code)?.let { notice(it) }
            }
        )
    }

    private fun micMessage(code: Int): String? = when (code) {
        -1 -> "Speech recognition is not available on this phone. Install or update the Google app."
        1, 2 -> "Voice input needs internet right now. Turn on data, or download an offline language pack in the Google app. Typing and simple commands still work."
        5 -> null
        6, 7, 0 -> "I didn't catch that. Tap the mic and try again."
        9 -> "Microphone permission is off. Turn it on in Settings > Permissions."
        else -> "Voice input failed (error $code). Try again or type your message."
    }

    fun send(text: String) {
        if (text.isBlank()) return
        if (thinking || agentRunning) {
            notice(if (agentRunning) "I'm still working on your last request. Tap Stop first." else "One moment, I'm still answering your last message.")
            return
        }
        val s = settings.value
        val app = getApplication<Application>()
        add(ChatMsg("user", text.trim(), persona = s.persona))

        // 1) Phone commands and custom shortcuts run locally: instant, no internet needed.
        val local = LocalCommands.parse(app, text, custom.toList())
        if (local != null && local.askAi == null) {
            viewModelScope.launch(safety) {
                var reply = local.reply
                val note = Actions.runAll(app, handleMemory(local.actions))
                speak(reply, s)
                if (note != null) reply += "\n\n($note)"
                add(ChatMsg("model", reply, persona = s.persona))
            }
            return
        }

        // 2) Everything else needs Gemini, which needs internet.
        if (!online) {
            notice(OFFLINE_MSG, s.persona)
            return
        }
        val aiPrompt = local?.askAi
        thinking = true
        viewModelScope.launch(safety) {
            var reply: String
            var isNotice = false
            var goalToRun: String? = null
            try {
                val apps = if (s.shareApps) withContext(Dispatchers.Default) { Actions.installedApps(app) } else emptyList()
                val system = Brain.buildSystem(s.prompt(), apps, custom.toList(), facts.toList())
                val hist = messages.filter { !it.system }.let { if (aiPrompt != null) it.dropLast(1) + ChatMsg("user", aiPrompt) else it }
                val r = withTimeout(20_000) { GeminiClient.ask(s.apiKey, s.model, system, hist) }
                reply = r.reply.ifBlank { "Done." }
                val acts = handleMemory(r.actions)
                goalToRun = acts.firstOrNull { it.optString("type") == "agent" }?.optString("goal")
                val note = Actions.runAll(app, acts.filter { it.optString("type") != "agent" })
                speak(reply, s)
                if (note != null) reply += "\n\n($note)"
            } catch (e: TimeoutCancellationException) {
                reply = "That took longer than 20 seconds, so I stopped. Check your connection and try again."
                isNotice = true
            } catch (e: IOException) {
                reply = OFFLINE_MSG
                isNotice = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply = "Error: ${e.message}"
                isNotice = true
            }
            thinking = false
            add(ChatMsg("model", reply, persona = s.persona, system = isNotice))
            goalToRun?.let { g ->
                if (s.agentOn) startAgent(g, s) else notice("Multi-step control is turned off in Settings.", s.persona)
            }
        }
    }

    // ---- agent mode: several steps across apps ----
    private fun startAgent(goal: String, s: AppSettings) {
        if (agentRunning) return
        agentRunning = true
        agentStatus = "Starting…"
        val app = getApplication<Application>()
        viewModelScope.launch(safety) {
            val apps = if (s.shareApps) withContext(Dispatchers.Default) { Actions.installedApps(app) } else emptyList()
            val result = try {
                Agent.run(app, goal, s, apps) { st ->
                    agentStatus = st
                    Toast.makeText(app, st, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Something went wrong: ${e.message}"
            }
            agentRunning = false
            agentStatus = ""
            speak(result, s)
            add(ChatMsg("model", result, persona = s.persona))
        }
    }

    fun stopAgent() { Agent.stop = true }

    /** Quietly repairs Screen control if the one-time secure-settings permission was granted. */
    fun healScreenControl() {
        runCatching {
            if (ShubhixAccessibilityService.instance == null) ShubhixAccessibilityService.tryAutoEnable(getApplication<Application>())
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
