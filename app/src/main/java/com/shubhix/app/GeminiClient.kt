package com.shubhix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL

data class ChatMsg(
    val role: String,            // "user" or "model"
    val text: String,
    val time: Long = System.currentTimeMillis(),
    val persona: String = ""
)
data class AiResult(val reply: String, val actions: List<JSONObject>)

object GeminiClient {
    private const val SPEC = """
You control an Android phone assistant. Answer ONLY with one JSON object:
{"reply":"short reply in the user's language","actions":[ ... ]}
Use "actions":[] when no phone action is needed. Each action is an object with "type" plus args, and an optional "wait_ms" delay before it runs.
Action types:
open_app{name}; play_youtube{query}; media{key:"play_pause"|"next"|"previous"}; tap_text{text}; tap_screen{x,y} (percent 0-100);
scroll{direction:"up"|"down"|"left"|"right"}; type_text{text}; back; home; recents; call{target} (name or number);
flashlight{on:true|false}; timer{seconds}; alarm{hour,minute,label}; volume{direction:"up"|"down"}; web_search{query}; open_url{url}.
When opening an app and then tapping inside it, add wait_ms of about 2500 on the later action.
For code, put it inside triple backticks inside "reply" with the language name after the opening backticks.
Never claim you did something the actions cannot do."""

    /** Tries twice. If there is no network at all it fails immediately instead of waiting. */
    suspend fun ask(apiKey: String, model: String, persona: String, history: List<ChatMsg>): AiResult {
        require(apiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
        var last: IOException? = null
        for (attempt in 0..1) {
            try {
                return request(apiKey, model, persona, history)
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

    private suspend fun request(apiKey: String, model: String, persona: String, history: List<ChatMsg>): AiResult =
        withContext(Dispatchers.IO) {
            val contents = JSONArray()
            history.takeLast(14).dropWhile { it.role != "user" }.forEach {
                contents.put(
                    JSONObject().put("role", it.role)
                        .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
                )
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", persona + "\n\n" + SPEC))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (code == 429 || code == 500 || code == 503) throw IOException("Google is busy (HTTP $code)")
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP $code")
                error(msg)
            }
            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parse(out)
        }

    private fun parse(raw: String): AiResult = try {
        val j = JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
        val arr = j.optJSONArray("actions") ?: JSONArray()
        AiResult(j.optString("reply", ""), (0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
    } catch (e: Exception) {
        AiResult(raw, emptyList())
    }
}
