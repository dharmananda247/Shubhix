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
    val retentionDays: Int = 30,        // 0 = keep chats forever
    val onboarded: Boolean = false,
    val voiceReply: Boolean = true,     // speak replies out loud
    val djVoice: String = "",           // "" = default voice
    val shubhuVoice: String = "",
    val djPitch: Float = 0.75f,         // lower = deeper
    val shubhuPitch: Float = 1.2f,
    val shareApps: Boolean = true,      // send installed app names to Gemini so it opens the right app
    val listenLang: String = "hi-IN",   // speech recognition language
    val agentOn: Boolean = true,        // allow multi-step screen control (reads on-screen text during a task)
    val bubbleOn: Boolean = false,      // floating avatar over other apps
    val theme: String = "gaming",       // "gaming" (neon) or "classic"
    val confirmFirstCall: Boolean = true,
    val musicApp: String = "",          // preferred music app package, "" = let Android choose
    val checklistDismissed: Boolean = false,
    val visionOn: Boolean = true        // agent may look at a screenshot when the screen cannot be read
) {
    fun prompt() = if (persona == "DJ") djPrompt else shubhuPrompt
    fun voiceName() = if (persona == "DJ") djVoice else shubhuVoice
    fun pitch() = if (persona == "DJ") djPitch else shubhuPitch
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
    private val kVoiceReply = booleanPreferencesKey("voice_reply")
    private val kDjVoice = stringPreferencesKey("dj_voice")
    private val kShubhuVoice = stringPreferencesKey("shubhu_voice")
    private val kDjPitch = floatPreferencesKey("dj_pitch")
    private val kShubhuPitch = floatPreferencesKey("shubhu_pitch")
    private val kShareApps = booleanPreferencesKey("share_apps")
    private val kListen = stringPreferencesKey("listen_lang")
    private val kAgent = booleanPreferencesKey("agent_on")
    private val kBubble = booleanPreferencesKey("bubble_on")
    private val kTheme = stringPreferencesKey("theme")
    private val kConfirmCall = booleanPreferencesKey("confirm_call")
    private val kMusic = stringPreferencesKey("music_app")
    private val kChecklist = booleanPreferencesKey("checklist_done")
    private val kVision = booleanPreferencesKey("vision_on")

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
            onboarded = p[kOnboarded] ?: d.onboarded,
            voiceReply = p[kVoiceReply] ?: d.voiceReply,
            djVoice = p[kDjVoice] ?: d.djVoice,
            shubhuVoice = p[kShubhuVoice] ?: d.shubhuVoice,
            djPitch = p[kDjPitch] ?: d.djPitch,
            shubhuPitch = p[kShubhuPitch] ?: d.shubhuPitch,
            shareApps = p[kShareApps] ?: d.shareApps,
            listenLang = p[kListen] ?: d.listenLang,
            agentOn = p[kAgent] ?: d.agentOn,
            bubbleOn = p[kBubble] ?: d.bubbleOn,
            theme = p[kTheme] ?: d.theme,
            confirmFirstCall = p[kConfirmCall] ?: d.confirmFirstCall,
            musicApp = p[kMusic] ?: d.musicApp,
            checklistDismissed = p[kChecklist] ?: d.checklistDismissed,
            visionOn = p[kVision] ?: d.visionOn
        )
    }

    suspend fun save(s: AppSettings) {
        ctx.store.edit {
            it[kApi] = s.apiKey; it[kModel] = s.model; it[kPersona] = s.persona
            it[kDj] = s.djPrompt; it[kShubhu] = s.shubhuPrompt; it[kSpeed] = s.speed
            it[kRetention] = s.retentionDays; it[kOnboarded] = s.onboarded
            it[kVoiceReply] = s.voiceReply
            it[kDjVoice] = s.djVoice; it[kShubhuVoice] = s.shubhuVoice
            it[kDjPitch] = s.djPitch; it[kShubhuPitch] = s.shubhuPitch
            it[kShareApps] = s.shareApps; it[kListen] = s.listenLang; it[kAgent] = s.agentOn; it[kBubble] = s.bubbleOn
            it[kTheme] = s.theme; it[kConfirmCall] = s.confirmFirstCall; it[kMusic] = s.musicApp
            it[kChecklist] = s.checklistDismissed; it[kVision] = s.visionOn
        }
    }
}
