package com.shubhix.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainVM(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val voice = Voice(app)

    val settings = prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val messages = mutableStateListOf<ChatMsg>()
    var thinking by mutableStateOf(false)
    var listening by mutableStateOf(false)

    fun setPersona(p: String) = viewModelScope.launch { prefs.save(settings.value.copy(persona = p)) }
    fun saveSettings(s: AppSettings) = viewModelScope.launch { prefs.save(s) }

    fun mic() {
        if (listening) return
        listening = true
        voice.listen(
            onText = { listening = false; send(it) },
            onEnd = { listening = false }
        )
    }

    fun send(text: String) {
        if (text.isBlank() || thinking) return
        val s = settings.value
        messages += ChatMsg("user", text.trim())
        thinking = true
        viewModelScope.launch {
            var reply: String
            try {
                val r = GeminiClient.ask(s.apiKey, s.model, s.prompt(), messages.toList())
                reply = r.reply.ifBlank { "Done." }
                val note = Actions.runAll(getApplication(), r.actions)
                voice.speak(reply, s.persona, s.speed)
                if (note != null) reply += "\n\n($note)"
            } catch (e: Exception) {
                reply = "Error: ${e.message}"
            }
            thinking = false
            messages += ChatMsg("model", reply)
        }
    }

    override fun onCleared() { voice.release() }
}
