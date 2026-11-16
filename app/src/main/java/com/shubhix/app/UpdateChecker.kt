package com.shubhix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(val latest: Int, val tag: String, val newer: Boolean)

/** Asks GitHub whether a newer build than the installed one has been released. */
object UpdateChecker {
    private const val URL = "https://api.github.com/repos/dharmananda247/Shubhix/releases/latest"
    const val DOWNLOAD = "https://github.com/dharmananda247/Shubhix/releases/latest/download/Shubhix.apk"

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val r = Net.getPublic(URL)
        if (r.first !in 200..299) error("GitHub answered with error " + r.first)
        val tag = JSONObject(r.second).optString("tag_name")
        val n = tag.substringAfterLast('-').toIntOrNull() ?: 0
        UpdateInfo(n, tag, n > BuildConfig.BUILD_NUMBER)
    }
}
