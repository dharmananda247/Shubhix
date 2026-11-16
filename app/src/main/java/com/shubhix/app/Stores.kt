package com.shubhix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A small list of strings saved in a private file (blocked apps, confirmed contacts). */
class ListStore(ctx: Context, name: String) {
    private val file = File(ctx.filesDir, name)

    fun load(): List<String> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(items: List<String>) {
        try {
            val a = JSONArray()
            items.forEach { a.put(it) }
            file.writeText(a.toString())
        } catch (e: Exception) {
            // never crash because of a convenience file
        }
    }
}

/** A small key to value map saved in a private file (nicknames such as momsy to Mom). */
class MapStore(ctx: Context, name: String) {
    private val file = File(ctx.filesDir, name)

    fun load(): Map<String, String> = try {
        val o = JSONObject(file.readText())
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.getString(k)
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(map: Map<String, String>) {
        try {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            file.writeText(o.toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}

data class TaskEntry(val goal: String, val result: String, val ok: Boolean, val time: Long)

/** The task log: what the agent was asked to do and how it went. */
class TaskStore(ctx: Context) {
    private val file = File(ctx.filesDir, "tasks.json")

    fun load(): List<TaskEntry> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { TaskEntry(it.getString("goal"), it.optString("result"), it.optBoolean("ok"), it.optLong("time")) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(items: List<TaskEntry>) {
        try {
            val a = JSONArray()
            items.takeLast(30).forEach {
                a.put(JSONObject().put("goal", it.goal).put("result", it.result).put("ok", it.ok).put("time", it.time))
            }
            file.writeText(a.toString())
        } catch (e: Exception) {
            // ignore
        }
    }
}

/** Settings that background parts of the app (not the screens) need to read. */
object Shared {
    @Volatile var musicApp: String = ""
}
