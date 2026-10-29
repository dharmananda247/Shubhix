package com.shubhix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the chat to a private file inside the app (not shared, not backed up). */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")

    fun load(retentionDays: Int): List<ChatMsg> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val cutoff = if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else 0L
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { ChatMsg(it.getString("role"), it.getString("text"), it.optLong("time", 0L), it.optString("persona")) }
                .filter { it.time >= cutoff }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<ChatMsg>) {
        try {
            val arr = JSONArray()
            list.takeLast(500).forEach {
                arr.put(JSONObject().put("role", it.role).put("text", it.text).put("time", it.time).put("persona", it.persona))
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // history is a convenience; never crash because of it
        }
    }
}
