package com.shubhix.app

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Short facts about the user that the personas remember. Stored only on this phone. */
class MemoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "memory.json")

    fun load(): List<String> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(facts: List<String>) {
        try {
            val a = JSONArray()
            facts.forEach { a.put(it) }
            file.writeText(a.toString())
        } catch (e: Exception) {
            // memory is a convenience; never crash because of it
        }
    }
}
