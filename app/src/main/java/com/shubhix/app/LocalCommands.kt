package com.shubhix.app

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Commands handled on the phone: instant, and no internet needed. */
object LocalCommands {

    /** askAi is set when a custom command should be sent to Gemini with that instruction. */
    data class Result(val reply: String, val actions: List<JSONObject>, val askAi: String? = null)

    private fun act(type: String, vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().put("type", type).also { o -> kv.forEach { o.put(it.first, it.second) } }

    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "व्हाट्स एप" to "whatsapp", "यूट्यूब" to "youtube",
        "कैमरा" to "camera", "इंस्टाग्राम" to "instagram", "क्रोम" to "chrome", "सेटिंग" to "settings",
        "सेटिंग्स" to "settings", "गैलरी" to "gallery", "फोन" to "phone", "मैसेज" to "messages",
        "कैलकुलेटर" to "calculator", "जीमेल" to "gmail", "मैप्स" to "maps", "टेलीग्राम" to "telegram",
        "फेसबुक" to "facebook", "स्नैपचैट" to "snapchat", "स्पॉटिफाई" to "spotify", "घड़ी" to "clock",
        "फोटोज" to "photos", "ड्राइव" to "drive", "प्ले स्टोर" to "play store"
    )

    private val openWords = listOf("open", "launch", "kholo", "khol", "खोलो", "खोल", "ओपन", "चालू करो")
    private val fillers = setOf("karo", "kar", "do", "dena", "please", "plz", "app", "the", "ko", "करो", "कर", "दो", "ऐप", "एप")

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parse(ctx: Context, raw: String, custom: List<CustomCmd>): Result? {
        val t = raw.lowercase().trim()
        if (t.isEmpty()) return null

        // 0) the user's own shortcuts come first
        val n = norm(raw)
        custom.firstOrNull { norm(it.phrase).length >= 3 && n.contains(norm(it.phrase)) }?.let { return fromCustom(it) }

        // 0.5) memory
        Regex("^(remember that|remember|yaad rakho|yaad rakhna|याद रखो|याद रखना)\\s+(.+)$").find(t)?.let {
            val fact = it.groupValues[2].trim()
            if (fact.length >= 3) return Result("Got it. I'll remember that.", listOf(act("remember", "fact" to fact)))
        }
        Regex("^(forget|bhool jao|भूल जाओ)\\s+(.+)$").find(t)?.let {
            val fact = it.groupValues[2].trim()
            if (fact.length >= 4) return Result("Okay, I've forgotten that.", listOf(act("forget", "fact" to fact)))
        }

        // 1) answers the phone can give by itself
        if (has(t, "read notification", "read my notification", "notification padho", "notification sunao", "नोटिफिकेशन पढ़ो", "नोटिफिकेशन सुनाओ")) {
            if (!NotificationStore.hasAccess(ctx))
                return Result("Turn on Notification access for Shubhix in Settings > Permissions first.", emptyList())
            val items = NotificationStore.latest(5)
            return if (items.isEmpty()) Result("You have no new notifications.", emptyList())
            else Result(
                "You have ${NotificationStore.total()} notifications. " +
                    items.joinToString(" ") { "${it.app}: ${it.title}. ${it.text}." },
                emptyList()
            )
        }
        if (has(t, "briefing", "सुबह की जानकारी")) {
            val b = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val now = Date()
            val counts = if (NotificationStore.hasAccess(ctx) && NotificationStore.total() > 0)
                " You have ${NotificationStore.total()} notifications: " +
                    NotificationStore.counts().entries.joinToString(", ") { "${it.key} ${it.value}" } + "."
            else ""
            return Result(
                "It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)} on " +
                    "${SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now)}. " +
                    "Battery is at ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%.$counts",
                emptyList()
            )
        }
        if (has(t, "what time", "time kya", "samay kya", "kitne baje", "कितने बजे", "समय क्या", "टाइम क्या"))
            return Result("It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())}.", emptyList())
        if (has(t, "what date", "today's date", "aaj ki date", "aaj kya tarikh", "आज की तारीख", "तारीख क्या", "आज कौन सा दिन"))
            return Result("Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())}.", emptyList())
        if (has(t, "battery", "बैटरी")) {
            val b = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return Result("Battery is at ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%.", emptyList())
        }

        // 2) phone controls
        if (has(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट")) {
            val off = has(t, " off", "band", "बंद", "बन्द", "disable", "turn off")
            return Result(if (off) "Torch off." else "Torch on.", listOf(act("flashlight", "on" to !off)))
        }
        if (has(t, "volume", "आवाज", "वॉल्यूम")) {
            val down = has(t, "down", "kam", "ghata", "decrease", "lower", "कम", "घटा")
            val up = has(t, "up", "badha", "zyada", "increase", "raise", "बढ़ा", "बढा", "ज्यादा")
            if (down || up) return Result("Volume ${if (down) "down" else "up"}.", listOf(act("volume", "direction" to if (down) "down" else "up")))
        }
        if (has(t, "next song", "next gana", "agla gana", "next track", "अगला गाना") || t == "next")
            return Result("Next.", listOf(act("media", "key" to "next")))
        if (has(t, "previous song", "pichla gana", "previous track", "पिछला गाना") || t == "previous")
            return Result("Previous.", listOf(act("media", "key" to "previous")))
        if (has(t, "pause", "रोको", "पॉज") || t == "play" || t == "stop music")
            return Result("Done.", listOf(act("media", "key" to "play_pause")))

        // 3) screen control (needs Accessibility)
        if (has(t, "scroll", "स्क्रॉल")) {
            val dir = when {
                has(t, "up", "upar", "ऊपर") -> "up"
                has(t, "left", "बाएं") -> "left"
                has(t, "right", "दाएं") -> "right"
                else -> "down"
            }
            return Result("Scrolling $dir.", listOf(act("scroll", "direction" to dir)))
        }
        if (has(t, "screenshot", "स्क्रीनशॉट")) return Result("Taking a screenshot.", listOf(act("system", "name" to "screenshot")))
        if (has(t, "lock screen", "lock phone", "lock the phone", "फोन लॉक", "स्क्रीन लॉक"))
            return Result("Locking.", listOf(act("system", "name" to "lock")))
        if (has(t, "notifications", "notification panel", "नोटिफिकेशन") && openWords.any { t.contains(it) })
            return Result("Opening notifications.", listOf(act("system", "name" to "notifications")))
        if (has(t, "recents", "recent apps", "रिसेंट")) return Result("Recent apps.", listOf(act("recents")))
        if (has(t, "go back", "back jao", "back karo", "पीछे", "वापस जाओ", "बैक") || t == "back")
            return Result("Going back.", listOf(act("back")))
        if (has(t, "go home", "home jao", "home screen", "होम") || t == "home")
            return Result("Going home.", listOf(act("home")))

        // 4) timer and call
        if (has(t, "timer", "टाइमर")) {
            val m = Regex("(\\d+)\\s*(hour|hr|घंटे|घंटा|minute|min|मिनट|second|sec|सेकंड)").find(t)
            if (m != null) {
                val num = m.groupValues[1].toInt()
                val u = m.groupValues[2]
                val secs = when {
                    u.startsWith("h") || u.startsWith("घ") -> num * 3600
                    u.startsWith("s") || u.startsWith("से") -> num
                    else -> num * 60
                }
                return Result("Timer set.", listOf(act("timer", "seconds" to secs)))
            }
        }
        if (has(t, "call", "कॉल", "फोन करो")) {
            val target = (Regex("call\\s+(.+?)(\\s+karo|\\s+kar do|$)").find(t)?.groupValues?.get(1)
                ?: Regex("(.+?)\\s+ko\\s+call").find(t)?.groupValues?.get(1))?.trim()
            if (!target.isNullOrBlank() && Actions.canCall(ctx, target))
                return Result("Calling $target.", listOf(act("call", "target" to target)))
        }

        // 5) open settings pages or apps (apps only when really installed, otherwise Gemini decides)
        val opening = openWords.any { t.contains(it) }
        if (opening && has(t, "wifi", "wi-fi", "वाईफाई")) return Result("Opening Wi-Fi settings.", listOf(act("system", "name" to "wifi")))
        if (opening && has(t, "bluetooth", "ब्लूटूथ")) return Result("Opening Bluetooth settings.", listOf(act("system", "name" to "bluetooth")))
        if (opening) {
            var name = t
            openWords.forEach { name = name.replace(it, " ") }
            name = name.split(" ").filter { it.isNotBlank() && it !in fillers }.joinToString(" ").trim()
            name = aliases[name] ?: name
            if (name.isNotEmpty() && Actions.hasApp(ctx, name))
                return Result("Opening $name.", listOf(act("open_app", "name" to name)))
        }
        return null
    }

    private fun fromCustom(c: CustomCmd): Result = when (c.type) {
        "open_app" -> Result("Opening ${c.value}.", listOf(act("open_app", "name" to c.value)))
        "open_url" -> Result("Opening the link.", listOf(act("open_url", "url" to (if (c.value.startsWith("http")) c.value else "https://" + c.value))))
        "call" -> Result("Calling ${c.value}.", listOf(act("call", "target" to c.value)))
        "youtube" -> Result("Searching YouTube.", listOf(act("play_youtube", "query" to c.value)))
        else -> Result("", emptyList(), askAi = c.value)
    }
}
