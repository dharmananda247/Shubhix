package com.shubhix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMsg(
    val role: String,            // "user" or "model"
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val persona: String = "",
    val system: Boolean = false   // app notices (errors, offline messages): shown in chat, never sent to the AI
)

data class AiResult(val reply: String, val actions: List<JSONObject>)

/** Builds the instructions Gemini receives, so it understands this phone and this user. */
object Brain {
    private const val SPEC = """
You are the brain of Shubhix, an Android phone assistant. Answer with ONE JSON object and nothing else:
{"reply":"...","actions":[ ... ]}
- "reply": what you say, short and natural, in the user's language (English, Hindi or Hinglish). Never claim something was done unless you included it in "actions".
- "actions": zero or more steps run in order. An optional "wait_ms" delays a step (use about 2500 after open_app before tapping inside that app).
Action types:
 open_app{name} (use the exact name from INSTALLED APPS when it is listed); play_youtube{query}; media{key:"play_pause"|"next"|"previous"};
 web_search{query}; open_url{url}; call{target} (contact name or number); flashlight{on:true|false}; timer{seconds,label};
 alarm{hour,minute,label} (24 hour clock); volume{direction:"up"|"down"};
 tap_text{text}; tap_screen{x,y} (percent 0-100); scroll{direction:"up"|"down"|"left"|"right"}; type_text{text}; back; home; recents;
 system{name:"wifi"|"bluetooth"|"settings"|"screenshot"|"lock"|"notifications"|"quick_settings"}
 remember{fact} (save something the user wants you to remember about them); forget{fact}
 agent{goal} - use this when the request needs SEVERAL steps inside an app, for example search and then play, send a message, find a setting or fill a field. Write goal as a complete instruction. It works step by step by looking at the screen. Do not use it for things the simple actions can do alone.
Rules:
- Understand informal, mixed-language or voice-typed phrases, for example "व्हाट्सएप खोल", "yaar gaana laga do", "torch band kar".
- If the request is unclear, ask ONE short question in "reply" and use "actions":[].
- For chat, facts or coding use "actions":[] and answer in "reply". Put code in triple backticks with the language name.
- Alarms: turn phrases like "kal subah 6 baje" into hour and minute using CURRENT TIME below.
- Screen actions (tap_text, scroll, type_text) work on the app that is on screen right now.
Examples:
User: open youtube and search lofi -> {"reply":"Opening YouTube for lofi.","actions":[{"type":"play_youtube","query":"lofi"}]}
User: open youtube search bollywood song and play it -> {"reply":"On it. I will search and play it.","actions":[{"type":"agent","goal":"Open YouTube, search for bollywood songs and play the first result"}]}
User: wake me at 6:30 -> {"reply":"Alarm set for 6:30 AM.","actions":[{"type":"alarm","hour":6,"minute":30,"label":"Wake up"}]}
User: whatsapp kholo aur search dabao -> {"reply":"Opening WhatsApp.","actions":[{"type":"open_app","name":"WhatsApp"},{"type":"tap_text","text":"Search","wait_ms":2500}]}
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

    /** Tries twice. With no network at all it fails immediately instead of waiting. */
    suspend fun ask(apiKey: String, model: String, system: String, history: List<ChatMsg>): AiResult {
        require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
        var last: IOException? = null
        for (attempt in 0..1) {
            try {
                return request(apiKey, model, system, history)
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
                    turns[turns.size - 1] = it.role to (turns.last().second + "\n" + it.text)
                } else {
                    turns.add(it.role to it.text)
                }
            }
            val contents = JSONArray()
            turns.forEach { (role, text) ->
                contents.put(
                    JSONObject().put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

            val (code, text) = call("$BASE/models/${model.trim()}:generateContent", apiKey, "POST", body)
            if (code == 429 || code == 500 || code == 503) throw IOException("Google is busy (HTTP $code)")
            if (code !in 200..299) error(friendly(code, text))
            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parse(out)
        }

    /** One question, one JSON answer. Used by agent mode for every step. */
    suspend fun oneShot(apiKey: String, model: String, system: String, user: String): String {
        var last: IOException? = null
        for (attempt in 0..1) {
            try {
                return oneShotRequest(apiKey, model, system, user)
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: ConnectException) {
                throw e
            } catch (e: IOException) {
                last = e
                delay(500)
            }
        }
        throw last ?: IOException("Network error")
    }

    private suspend fun oneShotRequest(apiKey: String, model: String, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
            val (code, text) = call("$BASE/models/${model.trim()}:generateContent", apiKey, "POST", body)
            if (code == 429 || code in 500..599) throw IOException("Google is busy (HTTP $code)")
            if (code !in 200..299) error(friendly(code, text))
            JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
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
        val (code, text) = call("$BASE/models/${model.trim()}:generateContent", apiKey, "POST", body)
        if (code !in 200..299) error(friendly(code, text))
    }

    /** Lists the models this key can really use, so a wrong model name is never a problem. */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter your API key first." }
        val (code, text) = call("$BASE/models?pageSize=200", apiKey, "GET", null)
        if (code !in 200..299) error(friendly(code, text))
        val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") }
            .sortedDescending()
    }

    private fun call(urlStr: String, apiKey: String, method: String, body: JSONObject?): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 15000
            setRequestProperty("x-goog-api-key", apiKey.trim())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun friendly(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
        return when {
            code == 400 && msg.contains("API key", true) -> "That API key is not valid. Copy it again from Google AI Studio."
            code == 401 || code == 403 -> "This key is not allowed to use that model (error $code). Check the key or pick another model."
            code == 404 -> "Model not found. Tap Choose model and pick one from the list."
            code == 429 -> "Quota reached. Wait a minute or pick another model."
            else -> msg
        }
    }

    private fun parse(raw: String): AiResult = try {
        val j = JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
        val arr = j.optJSONArray("actions") ?: JSONArray()
        AiResult(j.optString("reply", ""), (0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
    } catch (e: Exception) {
        AiResult(raw, emptyList())
    }
}
