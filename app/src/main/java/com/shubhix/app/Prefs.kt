package com.shubhix.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("shubhix_settings")

const val DEF_DJ = "You are DJ, the user's male AI companion inside the Shubhix assistant. " +
    "Tone: confident, caring, fun, boyfriend-like but always respectful and healthy. Never possessive or manipulative. " +
    "Reply in the user's language (Hinglish, Hindi or English). Keep replies short and natural."
const val DEF_SHUBHU = "You are SHUBHU, the user's female AI companion inside the Shubhix assistant. " +
    "Tone: sweet, caring, playful, girlfriend-like but always respectful and healthy. Never possessive or manipulative. " +
    "Reply in the user's language (Hinglish, Hindi or English). Keep replies short and natural."

data class AppSettings(
    val apiKey: String = "",
    val model: String = "gemini-3.8-flash",
    val persona: String = "SHUBHU",
    val djPrompt: String = DEF_DJ,
    val shubhuPrompt: String = DEF_SHUBHU,
    val speed: Float = 1f,
    val retentionDays: Int = 30,     // 0 = keep chats forever
    val onboarded: Boolean = false
) {
    fun prompt() = if (persona == "DJ") djPrompt else shubhuPrompt
}

class Prefs(private val ctx: Context) {
    private val kApi = stringPreferencesKey("api")
    private val kModel = stringPreferencesKey("model")
    private val kPersona = stringPreferencesKey("persona")
    private val kDj = stringPreferencesKey("dj")
    private val kShubhu = stringPreferencesKey("shubhu")
    private val kSpeed = floatPreferencesKey("speed")
    private val kRetention = intPreferencesKey("retention")
    private val kOnboarded = booleanPreferencesKey("onboarded")

    val flow = ctx.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            apiKey = p[kApi] ?: d.apiKey,
            model = p[kModel] ?: d.model,
            persona = p[kPersona] ?: d.persona,
            djPrompt = p[kDj] ?: d.djPrompt,
            shubhuPrompt = p[kShubhu] ?: d.shubhuPrompt,
            speed = p[kSpeed] ?: d.speed,
            retentionDays = p[kRetention] ?: d.retentionDays,
            onboarded = p[kOnboarded] ?: d.onboarded
        )
    }

    suspend fun save(s: AppSettings) {
        ctx.store.edit {
            it[kApi] = s.apiKey; it[kModel] = s.model; it[kPersona] = s.persona
            it[kDj] = s.djPrompt; it[kShubhu] = s.shubhuPrompt; it[kSpeed] = s.speed
            it[kRetention] = s.retentionDays; it[kOnboarded] = s.onboarded
        }
    }
}
