package com.shubhix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMsg(
    val role: String,            // "user" or "model"
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val persona: String = "",
    val system: Boolean = false, // app notices (errors, offline messages): shown in chat, never sent to the AI
    val voice: Boolean = false   // the text came from the microphone (shown as "Heard")
)

data class AiResult(val reply: String, val actions: List<JSONObject>)

/** Thrown when Google says the chosen model does not exist or cannot be used. */
class ModelNotFound(message: String) : Exception(message)

/** Builds the instructions Gemini receives, so it understands this phone and this user. */
object Brain {
    private const val SPEC = """
You are the brain of Shubhix AI, an Android phone assistant. Answer with ONE JSON object and nothing else:
{"reply":"...","actions":[ ... ]}
- "reply": short and natural, in the user's language (English, Hindi, Hinglish, Odia or Bengali). Say you did something ONLY if you included the matching action. If you cannot do something with the actions below, say so plainly and suggest what you can do instead.
- "actions": zero or more steps in order. An optional "wait_ms" delays a step.
Action types:
 open_app{name} (use the exact name from INSTALLED APPS when listed); call{target} (contact name or number); whatsapp{target,text,send:true|false}; sms{target,text}
 music{query} (play a song in the music app); play_youtube{query} (only opens YouTube search); youtube_play{query} (searches AND plays the first result)
 gallery; camera{mode:"photo"|"video"|"selfie"}; maps{query,navigate:true|false}
 media{key:"play_pause"|"next"|"previous"}; web_search{query}; open_url{url}; flashlight{on:true|false}; timer{seconds,label}; alarm{hour,minute,label} (24 hour clock); volume{direction:"up"|"down"}
 tap_text{text}; tap_screen{x,y} (percent 0-100); scroll{direction:"up"|"down"|"left"|"right"}; type_text{text}; back; home; recents
 settings_page{name:"wifi"|"bluetooth"|"battery"|"display"|"sound"|"location"|"apps"|"storage"|"accessibility"|"language"|"airplane"|"date"|"security"}
 system{name:"screenshot"|"lock"|"notifications"|"quick_settings"}
 remember{fact} (save something the user wants you to remember about them); forget{fact}
 agent{goal} - for tasks that need SEVERAL steps inside an app (find a setting, fill a form, send something with an attachment). Write goal as a complete instruction. Do not use it for anything the actions above can do alone.
Rules:
- Understand informal, mixed-language or voice-typed phrases, for example "व्हाट्सएप खोल", "yaar gaana laga do", "torch band kar".
- If the request is unclear, ask ONE short question in "reply" and use "actions":[].
- For chat, facts or coding use "actions":[] and answer in "reply". Put code in triple backticks with the language name.
- Alarms: turn phrases like "kal subah 6 baje" into hour and minute using CURRENT TIME below.
- Screen actions (tap_text, scroll, type_text) work on the app that is on screen right now.
Examples:
User: open youtube search bollywood song and play it -> {"reply":"On it. I will search and play it.","actions":[{"type":"youtube_play","query":"bollywood songs"}]}
User: call mummy -> {"reply":"Calling Mummy.","actions":[{"type":"call","target":"mummy"}]}
User: wake me at 6:30 -> {"reply":"Alarm set for 6:30 AM.","actions":[{"type":"alarm","hour":6,"minute":30,"label":"Wake up"}]}
User: what is GST -> {"reply":"GST is a single tax on goods and services in India...","actions":[]}
"""

    fun buildSystem(personaPrompt: String, apps: List<String>, custom: List<CustomCmd>, facts: List<String>): String = buildString {
        append(personaPrompt).append("\n\n").append(SPEC)
        append("\nCURRENT TIME: ").append(SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date()))
        if (facts.isNotEmpty()) {
            append("\nWHAT YOU KNOW ABOUT THE USER (use it naturally, never recite it unprompted):")
            facts.forEach { append("\n- ").append(it) }
        }
        if (apps.isNotEmpty()) append("\nINSTALLED APPS: ").append(apps.joinToString(", "))
        if (custom.isNotEmpty()) {
            append("\nUSER SHORTCUTS (when the user says the phrase, do this):")
            custom.forEach { append("\n- \"").append(it.phrase).append("\" -> ").append(it.type).append(": ").append(it.value) }
        }
    }
}

object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Set when the chosen model failed and another one worked. The app then saves the working model. */
    @Volatile var switchedTo: String? = null

    // ---------- public API ----------

    suspend fun ask(apiKey: String, model: String, system: String, history: List<ChatMsg>): AiResult {
        require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
        return withModelFallback(apiKey, model) { m -> retrying { request(apiKey, m, system, history) } }
    }

    /** One question, one JSON answer (optionally with a screenshot). Used by agent mode for every step. */
    suspend fun oneShot(apiKey: String, model: String, system: String, user: String, imageBase64: String? = null): String {
        require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
        return withModelFallback(apiKey, model) { m -> retrying { oneShotRequest(apiKey, m, system, user, imageBase64) } }
    }

    /** Sends a tiny request so Settings can show a clear success or the real reason it failed. */
    suspend fun test(apiKey: String, model: String) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter your API key first." }
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK")))
            )
        )
        val r = Net.call(BASE + "/models/" + model.trim() + ":generateContent", apiKey, "POST", body)
        if (r.first !in 200..299) error(friendly(r.first, r.second))
    }

    /** Lists the models this key can really use, so a wrong model name is never a problem. */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter your API key first." }
        val r = Net.call(BASE + "/models?pageSize=200", apiKey, "GET", null)
        if (r.first !in 200..299) error(friendly(r.first, r.second))
        val arr = JSONObject(r.second).optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") }
            .sortedDescending()
    }

    fun friendly(code: Int, text: String): String {
        val msg = errorMessage(text, "HTTP $code")
        return when {
            code == 400 && msg.contains("API key", true) -> "That API key is not valid. Copy it again from Google AI Studio."
            code == 401 || code == 403 -> "This key is not allowed to use that model (error $code). Check the key or pick another model."
            code == 404 -> "Model not found. Tap Choose model and pick one from the list."
            code == 429 -> "Quota reached. Wait a minute or pick another model."
            else -> msg
        }
    }

    // ---------- internals ----------

    private fun errorMessage(text: String, fallback: String): String =
        runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(fallback)

    private fun modelProblem(code: Int, text: String): Boolean {
        if (code == 404) return true
        if (code == 400) {
            val m = errorMessage(text, "").lowercase()
            return m.contains("not found") || m.contains("is not supported")
        }
        return false
    }

    /** If the chosen model is gone, quietly try the best working flash model instead. */
    private suspend fun <T> withModelFallback(apiKey: String, model: String, block: suspend (String) -> T): T {
        try {
            return block(model)
        } catch (first: ModelNotFound) {
            val listed = try { listModels(apiKey) } catch (e: Exception) { emptyList() }
            val good = listed.filter {
                it.contains("flash") && !it.contains("lite") && !it.contains("image") &&
                    !it.contains("tts") && !it.contains("live") && !it.contains("audio")
            }
            val candidates = (good + listOf("gemini-flash-latest")).distinct().filter { it != model }.take(4)
            for (m in candidates) {
                try {
                    val r = block(m)
                    switchedTo = m
                    return r
                } catch (e: ModelNotFound) {
                    continue
                }
            }
            throw first
        }
    }

    /** Tries twice. With no network at all it fails immediately instead of waiting. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: IOException? = null
        for (attempt in 0..1) {
            try {
                return block()
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: ConnectException) {
                throw e
            } catch (e: IOException) {
                last = e
                delay(600)
            }
        }
        throw last ?: IOException("Network error")
    }

    private suspend fun request(apiKey: String, model: String, system: String, history: List<ChatMsg>): AiResult =
        withContext(Dispatchers.IO) {
            // Gemini wants alternating user/model turns, so merge neighbours that share a role.
            val turns = mutableListOf<Pair<String, String>>()
            history.takeLast(14).dropWhile { it.role != "user" }.forEach {
                if (turns.isNotEmpty() && turns.last().first == it.role) {
                    turns[turns.size - 1] = Pair(it.role, turns.last().second + "\n" + it.text)
                } else {
                    turns.add(Pair(it.role, it.text))
                }
            }
            val contents = JSONArray()
            turns.forEach { t ->
                contents.put(
                    JSONObject().put("role", t.first)
                        .put("parts", JSONArray().put(JSONObject().put("text", t.second)))
                )
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

            val r = Net.call(BASE + "/models/" + model.trim() + ":generateContent", apiKey, "POST", body)
            if (r.first == 429 || r.first in 500..599) throw IOException("Google is busy (HTTP ${r.first})")
            if (modelProblem(r.first, r.second)) throw ModelNotFound(friendly(r.first, r.second))
            if (r.first !in 200..299) error(friendly(r.first, r.second))
            parse(extractText(r.second))
        }

    private suspend fun oneShotRequest(apiKey: String, model: String, system: String, user: String, imageBase64: String?): String =
        withContext(Dispatchers.IO) {
            val parts = JSONArray().put(JSONObject().put("text", user))
            if (imageBase64 != null) {
                parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)))
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
            val r = Net.call(BASE + "/models/" + model.trim() + ":generateContent", apiKey, "POST", body)
            if (r.first == 429 || r.first in 500..599) throw IOException("Google is busy (HTTP ${r.first})")
            if (modelProblem(r.first, r.second)) throw ModelNotFound(friendly(r.first, r.second))
            if (r.first !in 200..299) error(friendly(r.first, r.second))
            extractText(r.second)
        }

    private fun extractText(json: String): String =
        JSONObject(json).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

    private fun parse(raw: String): AiResult = try {
        val j = JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
        val arr = j.optJSONArray("actions") ?: JSONArray()
        AiResult(j.optString("reply", ""), (0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
    } catch (e: Exception) {
        AiResult(raw, emptyList())
    }
}
