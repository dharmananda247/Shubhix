package com.shubhix.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException

/** A yes/no question (options empty) or a "which one?" list shown to the user. The answer is an index, or -1 for no. */
class ConfirmRequest(val question: String, val options: List<String>) {
    val answer = CompletableDeferred<Int>()
}

private data class Outcome(val note: String?, val agentGoal: String?)

class MainVM(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val voice = Voice(app)
    private val history = HistoryStore(app)
    private val customStore = CustomStore(app)
    private val memoryStore = MemoryStore(app)
    private val nickStore = MapStore(app, "nicknames.json")
    private val blockStore = ListStore(app, "blocklist.json")
    private val confirmedStore = ListStore(app, "confirmed_contacts.json")
    private val taskStore = TaskStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // Initial onboarded=true avoids flashing the "choose companion" screen before settings load.
    val settings = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings(onboarded = true))
    val messages = mutableStateListOf<ChatMsg>()
    val custom = mutableStateListOf<CustomCmd>()
    val facts = mutableStateListOf<String>()
    val nicknames = mutableStateMapOf<String, String>()
    val blocklist = mutableStateListOf<String>()
    val tasks = mutableStateListOf<TaskEntry>()
    private val confirmed = mutableSetOf<String>()
    private var lastContact = ""

    var thinking by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var online by mutableStateOf(true)
    var agentRunning by mutableStateOf(false)
    var agentStatus by mutableStateOf("")
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)

    /** Set by the floating bubble so replies can pop up while another app is open. */
    var echo: ((String) -> Unit)? = null
    /** Set by the activity: opens Google's voice box when the built-in recogniser keeps failing. */
    var launchVoicePopup: (() -> Unit)? = null

    // Settings screen status
    var testOk by mutableStateOf<Boolean?>(null)
    var testMsg by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var modelsMsg by mutableStateOf("")
    var accTest by mutableStateOf("")
    var crash by mutableStateOf(CrashLog.read(app))
    var diag by mutableStateOf<List<Check>>(emptyList())
    var diagRunning by mutableStateOf(false)
    var updateMsg by mutableStateOf("")
    var updateNewer by mutableStateOf(false)
    var backupMsg by mutableStateOf("")
    var selfTest by mutableStateOf<List<TestRow>>(emptyList())

    private var micJob: Job? = null
    private var micTry = 0

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
        runCatching { nicknames.putAll(nickStore.load()) }
        runCatching { blocklist.addAll(blockStore.load()) }
        runCatching { confirmed.addAll(confirmedStore.load()) }
        runCatching { tasks.addAll(taskStore.load()) }
        viewModelScope.launch {
            runCatching {
                val s = prefs.flow.first()
                messages.addAll(history.load(s.retentionDays)) // chats past the retention limit are dropped here
                history.save(messages.toList())
            }
        }
        viewModelScope.launch { settings.collect { Shared.musicApp = it.musicApp } }
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

    private fun toast(text: String) {
        runCatching { Toast.makeText(getApplication<Application>(), text, Toast.LENGTH_SHORT).show() }
    }

    // ---------------- settings ----------------

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

    // ---------------- custom commands, memory, nicknames, blocked apps ----------------

    fun addCustom(c: CustomCmd) { custom.add(c); customStore.save(custom.toList()) }
    fun removeCustom(c: CustomCmd) { custom.remove(c); customStore.save(custom.toList()) }

    fun addFact(fact: String) {
        val f = fact.trim().take(200)
        if (f.length < 3 || facts.any { it.equals(f, ignoreCase = true) }) return
        facts.add(f)
        while (facts.size > 60) facts.removeAt(0)
        memoryStore.save(facts.toList())
    }

    fun removeFact(fact: String) { facts.remove(fact); memoryStore.save(facts.toList()) }
    fun clearFacts() { facts.clear(); memoryStore.save(emptyList()) }

    fun addNickname(alias: String, target: String) {
        val a = ContactBook.norm(ContactBook.roman(alias))
        if (a.length < 2 || target.isBlank()) return
        nicknames[a] = target.trim()
        nickStore.save(nicknames.toMap())
    }

    fun removeNickname(alias: String) { nicknames.remove(alias); nickStore.save(nicknames.toMap()) }

    fun addBlocked(name: String) {
        val n = name.trim().lowercase()
        if (n.length < 3 || blocklist.contains(n)) return
        blocklist.add(n)
        blockStore.save(blocklist.toList())
    }

    fun removeBlocked(name: String) { blocklist.remove(name); blockStore.save(blocklist.toList()) }

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

    // ---------------- task log ----------------

    private fun logTask(goal: String, result: String, ok: Boolean) {
        tasks.add(TaskEntry(goal, result.take(160), ok, System.currentTimeMillis()))
        while (tasks.size > 30) tasks.removeAt(0)
        taskStore.save(tasks.toList())
    }

    fun clearTasks() { tasks.clear(); taskStore.save(emptyList()) }

    fun runTask(goal: String) {
        val s = settings.value
        if (agentRunning || thinking) { notice("I'm busy with another request."); return }
        if (!s.agentOn) { notice("Multi-step control is turned off in Settings.", s.persona); return }
        add(ChatMsg("user", "Run again: $goal", persona = s.persona))
        startAgent(goal, s)
    }

    // ---------------- export, backup, restore ----------------

    /** Plain text of the chat, shortcuts and memory, for the Share sheet. */
    fun exportText(): String = buildString {
        append("Shubhix AI chat export\n\n")
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

    /** Everything except the API key, as JSON text. */
    fun exportBackup(): String {
        val s = settings.value
        val st = JSONObject()
            .put("persona", s.persona).put("model", s.model).put("speed", s.speed.toDouble())
            .put("retentionDays", s.retentionDays).put("voiceReply", s.voiceReply)
            .put("djVoice", s.djVoice).put("shubhuVoice", s.shubhuVoice)
            .put("djPitch", s.djPitch.toDouble()).put("shubhuPitch", s.shubhuPitch.toDouble())
            .put("shareApps", s.shareApps).put("listenLang", s.listenLang).put("agentOn", s.agentOn)
            .put("theme", s.theme).put("confirmFirstCall", s.confirmFirstCall).put("musicApp", s.musicApp)
            .put("visionOn", s.visionOn).put("djPrompt", s.djPrompt).put("shubhuPrompt", s.shubhuPrompt)
        val cmds = JSONArray()
        custom.forEach { cmds.put(JSONObject().put("phrase", it.phrase).put("type", it.type).put("value", it.value)) }
        val nicks = JSONObject()
        nicknames.forEach { (k, v) -> nicks.put(k, v) }
        return JSONObject()
            .put("app", "Shubhix AI").put("version", 1).put("settings", st)
            .put("custom", cmds).put("nicknames", nicks)
            .put("facts", JSONArray(facts.toList())).put("blocklist", JSONArray(blocklist.toList()))
            .put("confirmed", JSONArray(confirmed.toList()))
            .toString(2)
    }

    /** Restores a backup made by exportBackup. Returns a short message for the user. */
    fun importBackup(text: String): String = try {
        val o = JSONObject(text)
        if (o.optString("app") != "Shubhix AI") error("not a Shubhix AI backup")
        val st = o.optJSONObject("settings")
        if (st != null) {
            val d = settings.value
            saveSettings(
                d.copy(
                    persona = st.optString("persona", d.persona), model = st.optString("model", d.model),
                    speed = st.optDouble("speed", d.speed.toDouble()).toFloat(),
                    retentionDays = st.optInt("retentionDays", d.retentionDays),
                    voiceReply = st.optBoolean("voiceReply", d.voiceReply),
                    djVoice = st.optString("djVoice", d.djVoice), shubhuVoice = st.optString("shubhuVoice", d.shubhuVoice),
                    djPitch = st.optDouble("djPitch", d.djPitch.toDouble()).toFloat(),
                    shubhuPitch = st.optDouble("shubhuPitch", d.shubhuPitch.toDouble()).toFloat(),
                    shareApps = st.optBoolean("shareApps", d.shareApps), listenLang = st.optString("listenLang", d.listenLang),
                    agentOn = st.optBoolean("agentOn", d.agentOn), theme = st.optString("theme", d.theme),
                    confirmFirstCall = st.optBoolean("confirmFirstCall", d.confirmFirstCall),
                    musicApp = st.optString("musicApp", d.musicApp), visionOn = st.optBoolean("visionOn", d.visionOn),
                    djPrompt = st.optString("djPrompt", d.djPrompt), shubhuPrompt = st.optString("shubhuPrompt", d.shubhuPrompt)
                )
            )
        }
        val cmds = o.optJSONArray("custom")
        if (cmds != null) {
            custom.clear()
            for (i in 0 until cmds.length()) {
                val c = cmds.getJSONObject(i)
                custom.add(CustomCmd(c.getString("phrase"), c.getString("type"), c.getString("value")))
            }
            customStore.save(custom.toList())
        }
        val nicks = o.optJSONObject("nicknames")
        if (nicks != null) {
            nicknames.clear()
            val keys = nicks.keys()
            while (keys.hasNext()) { val k = keys.next(); nicknames[k] = nicks.getString(k) }
            nickStore.save(nicknames.toMap())
        }
        val f = o.optJSONArray("facts")
        if (f != null) { facts.clear(); for (i in 0 until f.length()) facts.add(f.getString(i)); memoryStore.save(facts.toList()) }
        val b = o.optJSONArray("blocklist")
        if (b != null) { blocklist.clear(); for (i in 0 until b.length()) blocklist.add(b.getString(i)); blockStore.save(blocklist.toList()) }
        val c = o.optJSONArray("confirmed")
        if (c != null) { confirmed.clear(); for (i in 0 until c.length()) confirmed.add(c.getString(i)); confirmedStore.save(confirmed.toList()) }
        "Restored. Your API key was not part of the backup, so it was kept as it is."
    } catch (e: Exception) {
        "That file is not a valid Shubhix AI backup."
    }

    // ---------------- Gemini key test, models, connection check, updates, self test ----------------

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
                testOk = false; testMsg = "Timed out after 20 seconds. Run Connection check."
            } catch (e: UnknownHostException) {
                testOk = false; testMsg = "Cannot find Google. Run Connection check."
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
            modelsMsg = "Timed out. Run Connection check."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelsMsg = e.message ?: "Could not load models."
        }
    }

    fun runDiagnostics(key: String, model: String) {
        diagRunning = true
        diag = emptyList()
        viewModelScope.launch {
            diag = try {
                withTimeout(45_000) { Diagnostics.run(getApplication<Application>(), key, model) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listOf(Check("Connection check", false, e.message ?: "The check failed."))
            }
            diagRunning = false
        }
    }

    fun checkUpdate() {
        updateMsg = "Checking…"
        updateNewer = false
        viewModelScope.launch {
            try {
                val u = withTimeout(15_000) { UpdateChecker.check() }
                updateNewer = u.newer
                updateMsg = if (u.newer) "A newer version is available (" + u.tag + ")." else "You have the latest version."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMsg = "Could not check: " + (e.message ?: "unknown error")
            }
        }
    }

    fun runSelfTest() {
        viewModelScope.launch {
            selfTest = withContext(Dispatchers.Default) { SelfTest.run(getApplication<Application>()) }
        }
    }

    fun testScreenControl() {
        viewModelScope.launch {
            val note = Actions.runOne(getApplication<Application>(), JSONObject().put("type", "system").put("name", "notifications"))
            accTest = note ?: "Working ✓ The notification panel should have opened."
        }
    }

    /** Quietly repairs Screen control if the one-time secure-settings permission was granted. */
    fun healScreenControl() {
        runCatching {
            if (ShubhixAccessibilityService.instance == null) ShubhixAccessibilityService.tryAutoEnable(getApplication<Application>())
        }
    }

    // ---------------- asking the user ----------------

    /**
     * Asks a yes/no question (no options) or a "which one?" question. Returns the chosen index, 0 for yes,
     * -1 for no or cancel, or null when there is no way to ask right now.
     */
    suspend fun ask(question: String, options: List<String> = emptyList()): Int? {
        val app = getApplication<Application>()
        if ((app as ShubhixApp).visible) {
            val req = ConfirmRequest(question, options)
            confirmRequest = req
            val r = withTimeoutOrNull(45_000) { req.answer.await() } ?: -1
            confirmRequest = null
            return r
        }
        if (options.isEmpty() && Settings.canDrawOverlays(app)) {
            return if (OverlayUi.confirm(app, question)) 0 else -1
        }
        return null
    }

    // ---------------- voice input ----------------

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
        micTry = 0
        startListening()
    }

    private fun startListening() {
        listening = true
        micJob?.cancel()
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
            onText = { micJob?.cancel(); listening = false; send(it, true) },
            onEnd = { code ->
                micJob?.cancel()
                listening = false
                onMicError(code)
            }
        )
    }

    /** Error 11 and friends mean Google's speech service dropped: retry once, then open Google's voice box. */
    private fun onMicError(code: Int) {
        val visible = (getApplication<Application>() as ShubhixApp).visible
        if ((code == 11 || code == 8 || code == 5) && micTry < 1) {
            micTry++
            viewModelScope.launch { delay(500); startListening() }
            return
        }
        val serviceError = code == 11 || code == 8 || code == 5 || code == 4 || code == -1
        if (serviceError && visible && launchVoicePopup != null) {
            notice("The voice service had a problem, so I'm opening Google's voice box instead.")
            runCatching { launchVoicePopup?.invoke() }
            return
        }
        micMessage(code)?.let { notice(it) }
    }

    fun onVoicePopupResult(text: String?) {
        if (text.isNullOrBlank()) notice("I didn't catch that. Tap the mic and try again.") else send(text, true)
    }

    private fun micMessage(code: Int): String? = when (code) {
        -1 -> "Speech recognition is not available on this phone. Install or update the Google app."
        1, 2 -> "Voice input needs internet right now. Turn on data, or download an offline language pack in the Google app. Typing and simple commands still work."
        3 -> "The microphone could not be used. Close other apps that use it and try again."
        5 -> null
        6, 7, 0 -> "I didn't catch that. Tap the mic and try again."
        9 -> "Microphone permission is off. Turn it on in Settings > Permissions."
        12, 13 -> "That listening language is not available on this phone. Pick another one in Settings > Voice."
        else -> "Voice input failed (error $code). Try again or type your message."
    }

    // ---------------- chat ----------------

    private fun claimsAction(reply: String) = Regex(
        "^(opening|calling|playing|turning|setting|sending|starting|searching|launching|enabling|disabling|closing)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(reply.trim())

    fun send(text: String, fromVoice: Boolean = false) {
        if (text.isBlank()) return
        if (thinking || agentRunning) {
            notice(if (agentRunning) "I'm still working on your last request. Tap Stop first." else "One moment, I'm still answering your last message.")
            return
        }
        val s = settings.value
        val app = getApplication<Application>()
        add(ChatMsg("user", text.trim(), persona = s.persona, voice = fromVoice))

        // 1) Phone commands and custom shortcuts run locally: instant, no internet needed.
        val local = LocalCommands.parse(app, text, custom.toList())
        if (local != null && local.askAi == null) {
            viewModelScope.launch(safety) {
                if (local.reply.isNotBlank()) {
                    add(ChatMsg("model", local.reply, persona = s.persona))
                    speak(local.reply, s)
                }
                val out = runActions(handleMemory(local.actions), s)
                out.note?.let { notice(it, s.persona) }
                val goal = local.agentGoal ?: out.agentGoal
                if (goal != null) launchAgentOrExplain(goal, s)
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
            var reply = ""
            var acts: List<JSONObject> = emptyList()
            var isNotice = false
            try {
                val apps = if (s.shareApps) withContext(Dispatchers.Default) { Actions.installedApps(app) } else emptyList()
                val system = Brain.buildSystem(s.prompt(), apps, custom.toList(), facts.toList())
                val hist = messages.filter { !it.system }.let { if (aiPrompt != null) it.dropLast(1) + ChatMsg("user", aiPrompt) else it }
                val r = withTimeout(25_000) { GeminiClient.ask(s.apiKey, s.model, system, hist) }
                reply = r.reply.ifBlank { "Done." }
                acts = handleMemory(r.actions)
                if (r.actions.isEmpty() && claimsAction(reply)) {
                    reply += "\n\n(I can't do that with a phone command, so nothing happened.)"
                }
                val switched = GeminiClient.switchedTo
                if (switched != null) {
                    GeminiClient.switchedTo = null
                    saveSettings(s.copy(model = switched))
                    notice("Your chosen model was not available, so I switched to $switched.")
                }
            } catch (e: TimeoutCancellationException) {
                reply = "That took longer than 25 seconds, so I stopped. Run Connection check in Settings."
                isNotice = true
            } catch (e: UnknownHostException) {
                reply = if (online) UNREACHABLE_MSG else OFFLINE_MSG
                isNotice = true
            } catch (e: IOException) {
                reply = "The connection dropped (${e.message}). Run Connection check in Settings if this keeps happening."
                isNotice = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply = "Error: ${e.message}"
                isNotice = true
            }
            thinking = false
            add(ChatMsg("model", reply, persona = s.persona, system = isNotice))
            if (!isNotice) {
                speak(reply, s)
                val out = runActions(acts, s)
                out.note?.let { notice(it, s.persona) }
                out.agentGoal?.let { launchAgentOrExplain(it, s) }
            }
        }
    }

    // ---------------- running actions ----------------

    private suspend fun runActions(acts: List<JSONObject>, s: AppSettings): Outcome {
        val app = getApplication<Application>()
        val notes = mutableListOf<String>()
        var goal: String? = null
        for (a in acts) {
            val wait = a.optLong("wait_ms", 0)
            if (wait > 0) delay(wait)
            when (a.optString("type")) {
                "call" -> callFlow(a.optString("target"), s)?.let { notes.add(it) }
                "whatsapp" -> whatsappFlow(a.optString("target"), a.optString("text"), a.optBoolean("send", false))?.let { notes.add(it) }
                "sms" -> smsFlow(a.optString("target"), a.optString("text"))?.let { notes.add(it) }
                "nickname" -> addNickname(a.optString("alias"), a.optString("target"))
                "youtube_play" -> {
                    val o = youtubeFlow(a.optString("query"))
                    o.note?.let { notes.add(it) }
                    if (o.agentGoal != null) goal = o.agentGoal
                }
                "agent" -> goal = a.optString("goal")
                else -> Actions.runOne(app, a)?.let { notes.add(it) }
            }
        }
        return Outcome(notes.joinToString("; ").ifEmpty { null }, goal)
    }

    private suspend fun youtubeFlow(query: String): Outcome {
        val app = getApplication<Application>()
        if (ShubhixAccessibilityService.instance == null) {
            Actions.runOne(app, JSONObject().put("type", "play_youtube").put("query", query))
            return Outcome("Opened YouTube search. Turn on Screen control to play the first result automatically.", null)
        }
        val ok = Recipes.youtubePlay(app, query) { st -> toast(st) }
        if (ok) return Outcome(null, null)
        return Outcome(null, "On YouTube, search for \"$query\" and play the first matching video")
    }

    /** Finds one contact (asking "which one?" if needed). Returns the contact, or a message to show. */
    private suspend fun resolveContact(target: String): Pair<ContactHit?, String?> {
        val app = getApplication<Application>()
        val raw = target.trim()
        if (raw.isEmpty()) return Pair(null, "Who should I contact?")
        val name = if (raw == "__last__") lastContact else raw
        if (name.isEmpty()) return Pair(null, "I don't have a previous contact yet.")
        if (name.none { it.isLetter() }) return Pair(ContactHit(name, name), null)   // a phone number
        if (!ContactBook.hasPermission(app)) return Pair(null, "Contacts permission is off. Turn it on in Settings > Permissions.")
        val hits = withContext(Dispatchers.IO) { ContactBook.search(app, name, nicknames.toMap()) }
        if (hits.isEmpty()) {
            return Pair(null, "I couldn't find \"$name\" in your contacts. Teach me by saying \"$name is Mom\" using the saved contact name.")
        }
        if (hits.size == 1) return Pair(hits[0], null)
        val idx = ask("Which one do you mean?", hits.map { it.name + " (" + it.number + ")" })
        if (idx == null) return Pair(hits[0], null)
        if (idx < 0) return Pair(null, "Okay, cancelled.")
        return Pair(hits[idx], null)
    }

    private fun rememberNickname(spoken: String, contactName: String) {
        val a = ContactBook.norm(ContactBook.roman(spoken))
        if (a.isNotEmpty() && a != ContactBook.norm(contactName) && !nicknames.containsKey(a)) addNickname(a, contactName)
    }

    private suspend fun callFlow(target: String, s: AppSettings): String? {
        val app = getApplication<Application>()
        val found = resolveContact(target)
        val hit = found.first ?: return found.second
        lastContact = hit.name
        if (target != "__last__" && target.any { it.isLetter() }) rememberNickname(target, hit.name)
        val key = hit.name.lowercase()
        if (s.confirmFirstCall && !confirmed.contains(key)) {
            val r = ask("Call " + hit.name + "?\n" + hit.number)
            if (r == null) return Actions.dial(app, hit.number, false) ?: "Opened the dialer for " + hit.name + "."
            if (r != 0) return "Okay, I won't call."
            confirmed.add(key)
            confirmedStore.save(confirmed.toList())
        }
        return Actions.dial(app, hit.number, true)
    }

    private suspend fun whatsappFlow(target: String, text: String, send: Boolean): String? {
        val app = getApplication<Application>()
        val found = resolveContact(target)
        val hit = found.first ?: return found.second
        lastContact = hit.name
        if (target.any { it.isLetter() }) rememberNickname(target, hit.name)

        var autoSend = false
        if (send && text.isNotBlank() && ShubhixAccessibilityService.instance != null) {
            val r = ask("Send to " + hit.name + ":\n\"" + text + "\"?")
            if (r != null && r < 0) return "Okay, I won't send it."
            autoSend = r == 0
        }
        val opened = Actions.whatsappOpen(app, hit.number, text)
        if (opened != null) {
            return Actions.smsOpen(app, hit.number, text) ?: "WhatsApp isn't installed, so I opened your messages app."
        }
        if (autoSend) {
            delay(2200)
            val svc = ShubhixAccessibilityService.instance ?: return "Screen control stopped. Please tap send yourself."
            for (i in 0 until 4) {
                if (svc.clickText("Send")) return "Sent."
                delay(900)
            }
            return "I could not find the Send button. Please tap send yourself."
        }
        return if (send && text.isNotBlank()) "The chat is open with your message ready. Tap send." else null
    }

    private suspend fun smsFlow(target: String, text: String): String? {
        val app = getApplication<Application>()
        val found = resolveContact(target)
        val hit = found.first ?: return found.second
        lastContact = hit.name
        return Actions.smsOpen(app, hit.number, text) ?: "Your message is ready in the messages app. Tap send."
    }

    // ---------------- agent mode ----------------

    private fun launchAgentOrExplain(goal: String, s: AppSettings) {
        if (s.agentOn) startAgent(goal, s) else notice("Multi-step control is turned off in Settings.", s.persona)
    }

    private fun startAgent(goal: String, s: AppSettings) {
        if (agentRunning) return
        agentRunning = true
        agentStatus = "Starting…"
        val app = getApplication<Application>()
        viewModelScope.launch(safety) {
            val apps = if (s.shareApps) withContext(Dispatchers.Default) { Actions.installedApps(app) } else emptyList()
            val result = try {
                Agent.run(app, goal, s, apps, blocklist.toList()) { st ->
                    agentStatus = st
                    toast(st)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentResult(false, "Something went wrong: ${e.message}")
            }
            agentRunning = false
            agentStatus = ""
            val switched = GeminiClient.switchedTo
            if (switched != null) {
                GeminiClient.switchedTo = null
                saveSettings(settings.value.copy(model = switched))
                notice("Your chosen model was not available, so I switched to $switched.")
            }
            logTask(goal, result.text, result.ok)
            speak(result.text, s)
            add(ChatMsg("model", result.text, persona = s.persona))
        }
    }

    fun stopAgent() { Agent.stop = true }

    override fun onCleared() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        voice.release()
    }

    companion object {
        const val OFFLINE_MSG = "No internet right now. Simple commands like \"open WhatsApp\", \"torch on\", " +
            "\"volume up\" or \"what time is it\" still work offline. Chat and coding need a connection."
        const val UNREACHABLE_MSG = "Your phone is online, but I can't reach Google. Open Settings > Connection check to see why."
    }
}
