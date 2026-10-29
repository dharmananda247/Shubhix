package com.shubhix.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

class MainVM(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val voice = Voice(app)
    private val history = HistoryStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    // Initial onboarded=true avoids flashing the "choose companion" screen before settings load.
    val settings = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings(onboarded = true))
    val messages = mutableStateListOf<ChatMsg>()
    var thinking by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var online by mutableStateOf(true)
    var testResult by mutableStateOf("")

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { online = true }
        override fun onLost(network: Network) { online = checkOnline() }
    }

    init {
        online = checkOnline()
        runCatching { cm.registerDefaultNetworkCallback(netCallback) }
        viewModelScope.launch {
            val s = prefs.flow.first()
            messages.addAll(history.load(s.retentionDays)) // old chats past the retention limit are dropped here
            history.save(messages.toList())
        }
    }

    private fun checkOnline(): Boolean {
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun add(m: ChatMsg) {
        messages += m
        history.save(messages.toList())
    }

    fun setPersona(p: String) { viewModelScope.launch { prefs.save(settings.value.copy(persona = p)) } }
    fun finishOnboarding(p: String) { viewModelScope.launch { prefs.save(settings.value.copy(persona = p, onboarded = true)) } }

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

    fun mic() {
        if (listening) return
        listening = true
        voice.listen(
            onText = { listening = false; send(it) },
            onEnd = { listening = false }
        )
    }

    fun testConnection(key: String, model: String) {
        testResult = "Testing…"
        viewModelScope.launch {
            testResult = try {
                withTimeout(20_000) {
                    GeminiClient.ask(key, model, "Reply with the single word OK.", listOf(ChatMsg("user", "Say OK")))
                }
                "Connected ✓"
            } catch (e: TimeoutCancellationException) {
                "Timed out after 20 seconds"
            } catch (e: IOException) {
                "No connection"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        val s = settings.value
        val app = getApplication<Application>()
        add(ChatMsg("user", text.trim(), persona = s.persona))

        // 1) Simple phone commands run on the phone: instant, no internet needed.
        val local = LocalCommands.parse(app, text)
        if (local != null) {
            viewModelScope.launch {
                var reply = local.reply
                val note = Actions.runAll(app, local.actions)
                voice.speak(reply, s.persona, s.speed)
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
        thinking = true
        viewModelScope.launch {
            var reply: String
            try {
                val r = withTimeout(20_000) {
                    GeminiClient.ask(s.apiKey, s.model, s.prompt(), messages.toList())
                }
                reply = r.reply.ifBlank { "Done." }
                val note = Actions.runAll(app, r.actions)
                voice.speak(reply, s.persona, s.speed)
                if (note != null) reply += "\n\n($note)"
            } catch (e: TimeoutCancellationException) {
                reply = "That took longer than 20 seconds, so I stopped. Check your connection and try again."
            } catch (e: IOException) {
                reply = OFFLINE_MSG
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
        const val OFFLINE_MSG = "No internet right now. Simple commands like \"open WhatsApp\", \"torch on\" or " +
            "\"volume up\" still work offline. Chat and coding need a connection."
    }
}
