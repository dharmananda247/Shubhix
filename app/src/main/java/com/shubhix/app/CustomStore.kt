package com.shubhix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** type is one of: open_app, open_url, call, youtube, ask_ai */
data class CustomCmd(val phrase: String, val type: String, val value: String)

class CustomStore(ctx: Context) {
    private val file = File(ctx.filesDir, "custom_commands.json")

    fun load(): List<CustomCmd> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { CustomCmd(it.getString("phrase"), it.getString("type"), it.getString("value")) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<CustomCmd>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("phrase", it.phrase).put("type", it.type).put("value", it.value)) }
            file.writeText(a.toString())
        } catch (e: Exception) {
            // shortcuts are a convenience; never crash because of them
        }
    }
}
