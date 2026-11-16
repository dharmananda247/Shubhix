package com.shubhix.app

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Tested step lists for common tasks. They are faster and more reliable than asking the AI
 * at every step. If a recipe cannot finish, the caller falls back to agent mode.
 */
object Recipes {

    private fun lower(s: String) = s.lowercase()

    private suspend fun waitForApp(svc: ShubhixAccessibilityService, part: String, seconds: Int): Screen? {
        for (i in 0 until seconds * 2) {
            val scr = svc.readScreen()
            if (scr != null && lower(scr.pkg).contains(part)) return scr
            delay(500)
        }
        return null
    }

    /** Opens YouTube, searches, and plays the first matching video. Returns true when playback was reached. */
    suspend fun youtubePlay(ctx: Context, query: String, status: (String) -> Unit): Boolean {
        val svc = ShubhixAccessibilityService.instance ?: return false
        status("Opening YouTube")
        val open = Actions.runOne(ctx, JSONObject().put("type", "open_app").put("name", "youtube"))
        if (open != null) return false
        var scr = waitForApp(svc, "youtube", 9) ?: return false
        delay(1200)

        status("Searching")
        scr = svc.readScreen() ?: return false
        var input = scr.nodes.firstOrNull { it.kind == "input" }
        if (input == null) {
            val searchIcon = scr.nodes.firstOrNull { it.kind == "button" && lower(it.label).contains("search") } ?: return false
            if (!svc.tapNode(scr, searchIcon.id)) return false
            delay(1200)
            scr = svc.readScreen() ?: return false
            input = scr.nodes.firstOrNull { it.kind == "input" }
        }
        if (input == null) return false
        if (svc.typeInto(scr, input.id, query, true) != null) return false
        delay(2500)

        status("Choosing a video")
        scr = svc.readScreen() ?: return false
        var video = pickVideo(scr)
        if (video == null) {
            // Search may not have been submitted: tap the suggestion that matches the query.
            val sug = scr.nodes.firstOrNull { it.kind == "button" && lower(it.label).contains(lower(query)) }
            if (sug != null) {
                svc.tapNode(scr, sug.id)
                delay(2500)
                scr = svc.readScreen() ?: return false
                video = pickVideo(scr)
            }
        }
        if (video == null) return false
        if (!svc.tapNode(scr, video.id)) return false
        delay(3000)

        val playing = svc.readScreen() ?: return false
        return playing.nodes.any {
            val l = lower(it.label)
            l.contains("minimi") || l.contains("pause") || l.contains("full screen") || l.contains("fullscreen")
        }
    }

    private fun pickVideo(scr: Screen): UiNode? = scr.nodes.firstOrNull {
        val l = lower(it.label)
        it.kind == "button" && it.label.length >= 25 && !l.startsWith("search") &&
            (l.contains("minutes") || l.contains("seconds") || l.contains("views") || l.contains("play video"))
    }
}
