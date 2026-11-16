package com.shubhix.app

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Commands handled on the phone: instant, and no internet needed. */
object LocalCommands {

    /**
     * askAi: send this instruction to Gemini (custom "Ask AI" shortcut).
     * agentGoal: start agent mode with this goal (custom "Phone task" shortcut).
     */
    data class Result(
        val reply: String,
        val actions: List<JSONObject>,
        val askAi: String? = null,
        val agentGoal: String? = null
    )

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

    private val ytStop = setOf(
        "open", "launch", "youtube", "on", "in", "at", "and", "then", "search", "for", "play", "it", "the", "please",
        "pe", "par", "mein", "me", "chalao", "chala", "laga", "do", "karo", "kar", "kholo", "khol", "bajao",
        "यूट्यूब", "पर", "में", "चलाओ", "चला", "लगा", "दो", "करो", "खोलो", "ओपन", "बजाओ", "और", "सर्च"
    )
    private val musicStop = setOf(
        "ka", "ke", "ki", "gana", "gaana", "gaane", "chalao", "chala", "laga", "do", "bajao", "play", "karo", "kar",
        "का", "के", "की", "गाना", "गाने", "चलाओ", "चला", "लगा", "दो", "बजाओ", "करो", "please"
    )

    private val pageKeys = listOf(
        Pair(listOf("battery usage", "battery settings", "battery saver", "battery"), "battery"),
        Pair(listOf("wifi", "wi-fi", "वाईफाई"), "wifi"),
        Pair(listOf("bluetooth", "ब्लूटूथ"), "bluetooth"),
        Pair(listOf("display", "brightness", "dark mode", "screen timeout"), "display"),
        Pair(listOf("sound", "ringtone", "volume settings"), "sound"),
        Pair(listOf("location", "gps"), "location"),
        Pair(listOf("storage"), "storage"),
        Pair(listOf("accessibility"), "accessibility"),
        Pair(listOf("language"), "language"),
        Pair(listOf("airplane", "flight mode"), "airplane"),
        Pair(listOf("security"), "security"),
        Pair(listOf("apps", "app settings"), "apps")
    )

    private val lead = Regex("^((hi|hey|hello|ok|okay|please|plz|dj|shubhu|shubhix|ai|bro|yaar|sir|हाय|हेलो|प्लीज)[,!.]?\\s+)+")
    private val tail = Regex("(\\s+(please|plz|ok|now|karo|kar do|kardo|कृपया|प्लीज))+$")

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Drops greetings and politeness words so "hi open whatsapp please" becomes "open whatsapp". */
    private fun clean(s: String): String {
        val c = s.replace(lead, "").replace(tail, "").trim()
        return if (c.isEmpty()) s else c
    }

    private fun stripWords(text: String, stop: Set<String>) =
        text.split(" ").filter { it.isNotBlank() && it !in stop }.joinToString(" ").trim()

    private fun title(s: String) = s.split(" ").joinToString(" ") { w ->
        if (w.isNotEmpty() && w[0].isLowerCase() && w[0].code < 128) w.replaceFirstChar { c -> c.uppercase() } else w
    }

    /** "call rakhi and tap call" -> "rakhi" */
    private fun callTarget(t0: String): String? {
        var t = t0
        t = t.replace(Regex("\\b(and|then|aur)\\s+(tap|press|click|hit|dial)(\\s+on)?(\\s+the)?(\\s+(call|green|dial)(\\s+button)?)?"), " ")
        t = t.replace(Regex("\\b(please|plz|now|jaldi)\\b"), " ").replace(Regex("(करो|कर दो|कर दीजिए)"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        val a = Regex("^(?:call|phone|ring|dial)\\s+(?:to\\s+)?(.+)$").find(t)
        if (a != null) return a.groupValues[1].trim()
        val b = Regex("^(.+?)\\s+(?:ko|को)\\s+(?:call|phone|कॉल|फोन)(?:\\s.*)?$").find(t)
        if (b != null) return b.groupValues[1].trim()
        val c = Regex("^(.+?)\\s+(?:कॉल|फोन)(?:\\s.*)?$").find(t)
        if (c != null) return c.groupValues[1].trim()
        return null
    }

    fun parse(ctx: Context, raw: String, custom: List<CustomCmd>): Result? {
        val t0 = raw.lowercase().trim()
        if (t0.isEmpty()) return null
        val t = clean(t0)

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
        // "momsy is mom" teaches a nickname, but only when the second part is a real contact
        Regex("^([\\p{L}\\p{M}\\p{N}]+)\\s+(is|means|=|मतलब)\\s+(.+)$").find(t)?.let {
            val alias = it.groupValues[1]
            val target = it.groupValues[3].trim()
            val notNames = setOf("what", "who", "where", "when", "why", "how", "this", "that", "there", "here", "today", "tomorrow", "yesterday", "which", "whose")
            if (alias.length >= 3 && alias !in notNames && ContactBook.hasPermission(ctx) && ContactBook.search(ctx, alias, emptyMap()).isEmpty()) {
                val hit = ContactBook.search(ctx, target, emptyMap()).firstOrNull()
                if (hit != null && alias != ContactBook.norm(hit.name)) {
                    return Result("Okay, $alias now means ${hit.name}.", listOf(act("nickname", "alias" to alias, "target" to hit.name)))
                }
            }
        }

        // 1) answers the phone can give by itself
        if (has(t, "read notification", "read my notification", "notification padho", "notification sunao", "नोटिफिकेशन पढ़ो", "नोटिफिकेशन सुनाओ")) {
            if (!NotificationStore.hasAccess(ctx))
                return Result("Turn on Notification access for Shubhix AI in Settings > Permissions first.", emptyList())
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
        if (has(t, "battery level", "battery percent", "how much battery", "बैटरी कितनी", "बैटरी लेवल") || t == "battery") {
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
        val opening = openWords.any { t.contains(it) }
        if (has(t, "notifications", "notification panel", "नोटिफिकेशन") && opening)
            return Result("Opening notifications.", listOf(act("system", "name" to "notifications")))
        if (has(t, "recents", "recent apps", "रिसेंट")) return Result("Recent apps.", listOf(act("recents")))
        if (has(t, "go back", "back jao", "back karo", "पीछे", "वापस जाओ", "बैक") || t == "back")
            return Result("Going back.", listOf(act("back")))
        if (has(t, "go home", "home jao", "home screen", "होम") || t == "home")
            return Result("Going home.", listOf(act("home")))

        // 4) timer
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

        // 5) people: calls, WhatsApp, SMS
        if (has(t, "call again", "redial", "phir se call", "फिर से कॉल"))
            return Result("Calling again.", listOf(act("call", "target" to "__last__")))
        if (has(t, "call", "कॉल", "फोन", "phone", "ring ", "dial")) {
            val target = callTarget(t)
            if (!target.isNullOrBlank()) return Result("Calling ${title(target)}.", listOf(act("call", "target" to target)))
        }
        val mentionsWa = has(t, "whatsapp", "व्हाट्सएप", "व्हाट्सऐप")
        val send = Regex(
            "^(?:send\\s+(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text)\\s+to|message|msg|whatsapp|text|sms)\\s+(.+?)" +
                "(?:\\s+(?:on|in|via)\\s+whatsapp)?\\s+(?:saying|that says|that|:)\\s+(.+)$"
        ).find(t)
        if (send != null) {
            val name = send.groupValues[1].trim()
            val text = send.groupValues[2].trim()
            if (name.isNotEmpty() && text.isNotEmpty()) {
                val sms = t.startsWith("sms ") || (t.startsWith("text ") && !mentionsWa)
                return if (sms) Result("Texting ${title(name)}.", listOf(act("sms", "target" to name, "text" to text)))
                else Result("Messaging ${title(name)}.", listOf(act("whatsapp", "target" to name, "text" to text, "send" to true)))
            }
        }
        val chat = Regex("^(?:message|msg|chat with|open chat with)\\s+(.+?)\\s+(?:on|in|via)\\s+whatsapp$").find(t)
        if (chat != null) {
            val name = chat.groupValues[1].trim()
            return Result("Opening chat with ${title(name)}.", listOf(act("whatsapp", "target" to name, "text" to "", "send" to false)))
        }

        // 6) places
        val nav = Regex("^(?:navigate|directions?|route|take me|drive)\\s+(?:to\\s+)?(.+)$").find(t)
            ?: Regex("^go\\s+to\\s+(.+)$").find(t)
        if (nav != null && nav.groupValues[1].isNotBlank() &&
            nav.groupValues[1].trim() !in setOf("home", "back", "sleep", "bed", "settings", "the home screen")
        ) return Result("Starting navigation.", listOf(act("maps", "query" to nav.groupValues[1].trim(), "navigate" to true)))
        val navHindi = Regex("^(.+?)\\s+(?:ka rasta|ka route|ka direction|का रास्ता)$").find(t)
        if (navHindi != null)
            return Result("Starting navigation.", listOf(act("maps", "query" to navHindi.groupValues[1].trim(), "navigate" to true)))
        val onMap = Regex("^(?:show|find|search)\\s+(.+?)\\s+(?:on\\s+)?(?:the\\s+)?maps?$").find(t)
        if (onMap != null)
            return Result("Showing it on the map.", listOf(act("maps", "query" to onMap.groupValues[1].trim(), "navigate" to false)))

        // 7) gallery and camera
        if (has(t, "gallery", "गैलरी", "my photos", "photos app", "open photos") && (opening || has(t, "show", "dikhao", "दिखाओ") || t == "gallery"))
            return Result("Opening the gallery.", listOf(act("gallery")))
        if (has(t, "camera", "कैमरा", "selfie", "take a photo", "take a picture", "take photo", "record a video", "video record") &&
            (opening || has(t, "take", "record", "selfie", "खींच")) ) {
            val mode = when {
                has(t, "selfie") -> "selfie"
                has(t, "video") -> "video"
                else -> "photo"
            }
            return Result("Opening the camera.", listOf(act("camera", "mode" to mode)))
        }

        // 8) YouTube and music
        if (has(t, "youtube", "यूट्यूब")) {
            val q = stripWords(t, ytStop)
            val playWords = has(t, "play", "chalao", "chala", "laga", "bajao", "चलाओ", "चला", "लगा", "बजाओ")
            if (q.isNotEmpty()) {
                return if (playWords) Result("Playing on YouTube.", listOf(act("youtube_play", "query" to q)))
                else Result("Searching YouTube.", listOf(act("play_youtube", "query" to q)))
            }
        }
        val play = Regex("^(?:play|bajao|chalao|laga do)\\s+(.+)$").find(t)
        if (play != null) {
            val q = stripWords(play.groupValues[1], setOf("please", "now"))
            if (q.isNotEmpty()) return Result("Playing $q.", listOf(act("music", "query" to q)))
        }
        if (has(t, "gana", "gaana", "gaane", "गाना", "गाने") && has(t, "chalao", "laga", "bajao", "चलाओ", "लगा", "बजाओ")) {
            val q = stripWords(t, musicStop)
            if (q.isNotEmpty()) return Result("Playing $q.", listOf(act("music", "query" to q)))
        }

        // 9) settings pages
        val saysSettings = has(t, "settings", "सेटिंग")
        if (saysSettings || opening) {
            val safeWithoutWord = setOf("wifi", "bluetooth", "airplane", "battery", "display")
            for (p in pageKeys) {
                if (p.first.any { t.contains(it) } && (saysSettings || p.second in safeWithoutWord))
                    return Result("Opening ${p.second} settings.", listOf(act("settings_page", "name" to p.second)))
            }
        }

        // 10) open an app (only when it is really installed, otherwise Gemini decides)
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
        "task" -> Result("On it.", emptyList(), agentGoal = c.value)
        else -> Result("", emptyList(), askAi = c.value)
    }
}
