package com.shubhix.app

import android.content.Context
import org.json.JSONObject

/** Simple commands handled on the phone, so they work with no internet and reply instantly. */
object LocalCommands {

    data class Result(val reply: String, val actions: List<JSONObject>)

    private fun act(type: String, vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().put("type", type).also { o -> kv.forEach { o.put(it.first, it.second) } }

    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "यूट्यूब" to "youtube", "कैमरा" to "camera",
        "इंस्टाग्राम" to "instagram", "क्रोम" to "chrome", "सेटिंग" to "settings", "सेटिंग्स" to "settings",
        "गैलरी" to "gallery", "फोन" to "phone", "मैसेज" to "messages", "कैलकुलेटर" to "calculator",
        "जीमेल" to "gmail", "मैप्स" to "maps", "टेलीग्राम" to "telegram", "फेसबुक" to "facebook"
    )

    private val openWords = listOf("open", "launch", "kholo", "khol", "खोलो", "खोल", "ओपन", "चालू करो")
    private val fillers = setOf("karo", "kar", "do", "dena", "please", "plz", "app", "the", "ko", "करो", "कर", "दो", "ऐप", "एप")

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    fun parse(ctx: Context, raw: String): Result? {
        val t = raw.lowercase().trim()
        if (t.isEmpty()) return null

        // torch
        if (has(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट")) {
            val off = has(t, " off", "band", "बंद", "बन्द", "disable", "turn off")
            return Result(if (off) "Torch off." else "Torch on.", listOf(act("flashlight", "on" to !off)))
        }
        // volume
        if (has(t, "volume", "आवाज", "वॉल्यूम")) {
            val down = has(t, "down", "kam", "ghata", "decrease", "lower", "कम", "घटा")
            val up = has(t, "up", "badha", "zyada", "increase", "raise", "बढ़ा", "बढा", "ज्यादा")
            if (down || up) return Result("Volume ${if (down) "down" else "up"}.", listOf(act("volume", "direction" to if (down) "down" else "up")))
        }
        // media
        if (has(t, "next song", "next gana", "agla gana", "next track", "अगला गाना") || t == "next")
            return Result("Next.", listOf(act("media", "key" to "next")))
        if (has(t, "previous song", "pichla gana", "previous track", "पिछला गाना") || t == "previous")
            return Result("Previous.", listOf(act("media", "key" to "previous")))
        if (has(t, "pause", "रोको", "पॉज") || t == "play" || t == "stop music")
            return Result("Done.", listOf(act("media", "key" to "play_pause")))
        // navigation / scroll
        if (has(t, "scroll", "स्क्रॉल")) {
            val dir = when {
                has(t, "up", "upar", "ऊपर") -> "up"
                has(t, "left", "बाएं") -> "left"
                has(t, "right", "दाएं") -> "right"
                else -> "down"
            }
            return Result("Scrolling $dir.", listOf(act("scroll", "direction" to dir)))
        }
        if (has(t, "recents", "recent apps", "रिसेंट")) return Result("Recent apps.", listOf(act("recents")))
        if (has(t, "go back", "back jao", "back karo", "पीछे", "वापस जाओ") || t == "back")
            return Result("Going back.", listOf(act("back")))
        if (has(t, "go home", "home jao", "home screen", "होम") || t == "home")
            return Result("Going home.", listOf(act("home")))
        // timer
        if (has(t, "timer", "टाइमर")) {
            val m = Regex("(\\d+)\\s*(hour|hr|घंटे|घंटा|minute|min|मिनट|second|sec|सेकंड)").find(t)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                val u = m.groupValues[2]
                val secs = when {
                    u.startsWith("h") || u.startsWith("घ") -> n * 3600
                    u.startsWith("s") || u.startsWith("से") -> n
                    else -> n * 60
                }
                return Result("Timer set.", listOf(act("timer", "seconds" to secs)))
            }
        }
        // call
        if (has(t, "call", "कॉल", "फोन करो")) {
            val target = (Regex("call\\s+(.+?)(\\s+karo|\\s+kar do|$)").find(t)?.groupValues?.get(1)
                ?: Regex("(.+?)\\s+ko\\s+call").find(t)?.groupValues?.get(1))?.trim()
            if (!target.isNullOrBlank() && Actions.canCall(ctx, target))
                return Result("Calling $target.", listOf(act("call", "target" to target)))
        }
        // open app (only when the app is really installed, otherwise let Gemini handle it)
        if (openWords.any { t.contains(it) }) {
            var name = t
            openWords.forEach { name = name.replace(it, " ") }
            name = name.split(" ").filter { it.isNotBlank() && it !in fillers }.joinToString(" ").trim()
            name = aliases[name] ?: name
            if (name.isNotEmpty() && Actions.hasApp(ctx, name))
                return Result("Opening $name.", listOf(act("open_app", "name" to name)))
        }
        return null
    }
}
