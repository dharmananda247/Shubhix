package com.shubhix.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/** Executes simple phone actions. Each returns null on success, or a short note about the problem. */
object Actions {

    private const val NEED_ACC = "Screen control is off. Open Shubhix AI Settings > Permissions and turn it on in Accessibility."
    private const val STUCK_ACC = "Screen control is turned on but not running. Switch Shubhix AI off and on in Accessibility settings."

    suspend fun runAll(ctx: Context, list: List<JSONObject>): String? {
        val notes = mutableListOf<String>()
        for (a in list) {
            val wait = a.optLong("wait_ms", 0)
            if (wait > 0) delay(wait)
            run(ctx, a)?.let { notes.add(it) }
        }
        return notes.joinToString("; ").ifEmpty { null }
    }

    /** Runs a single action. Returns null on success or a short note. */
    fun runOne(ctx: Context, a: JSONObject): String? = run(ctx, a)

    private fun go(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun run(ctx: Context, a: JSONObject): String? = try {
        when (a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "play_youtube" -> youtube(ctx, a.optString("query"))
            "music" -> music(ctx, a.optString("query"))
            "media" -> media(ctx, a.optString("key"))
            "gallery" -> gallery(ctx)
            "camera" -> camera(ctx, a.optString("mode", "photo"))
            "maps" -> maps(ctx, a.optString("query"), a.optBoolean("navigate", true))
            "settings_page" -> settingsPage(ctx, a.optString("name"))
            "web_search" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(a.optString("query"))))); null }
            "open_url" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url")))); null }
            "call" -> dial(ctx, a.optString("target"), false)
            "flashlight" -> torch(ctx, a.optBoolean("on", true))
            "timer" -> {
                go(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Shubhix timer"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); null
            }
            "alarm" -> {
                go(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Shubhix"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); null
            }
            "volume" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val d = if (a.optString("direction") == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI); null
            }
            "system" -> system(ctx, a.optString("name"))
            "tap_text", "tap_screen", "scroll", "type_text", "back", "home", "recents" -> screen(ctx, a)
            else -> null
        }
    } catch (e: Exception) {
        "Action failed: ${e.message}"
    }

    // ---- Screen control (needs the Accessibility service) ----

    private fun noService(ctx: Context) = if (ShubhixAccessibilityService.isEnabled(ctx)) STUCK_ACC else NEED_ACC

    private fun screen(ctx: Context, a: JSONObject): String? {
        val s = ShubhixAccessibilityService.instance ?: return noService(ctx)
        val ok = when (a.optString("type")) {
            "tap_text" -> s.clickText(a.optString("text"))
            "tap_screen" -> s.tapPercent(a.optDouble("x", 50.0).toFloat(), a.optDouble("y", 50.0).toFloat())
            "scroll" -> s.scroll(a.optString("direction", "down"))
            "type_text" -> s.typeText(a.optString("text"))
            "back" -> s.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.global(AccessibilityService.GLOBAL_ACTION_HOME)
            else -> s.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        return if (ok) null else "That screen action did not work here (nothing matched on this screen)."
    }

    private fun system(ctx: Context, name: String): String? {
        when (name) {
            "wifi", "bluetooth", "settings" -> return settingsPage(ctx, name)
        }
        val s = ShubhixAccessibilityService.instance ?: return noService(ctx)
        val code = when (name) {
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Unknown system action '$name'"
        }
        return if (s.global(code)) null else "This phone did not allow that action."
    }

    private val settingsPages = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "settings" to "android.settings.SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "apps" to "android.settings.APPLICATION_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "security" to "android.settings.SECURITY_SETTINGS"
    )

    private fun settingsPage(ctx: Context, name: String): String? {
        val action = settingsPages[name.lowercase().trim()] ?: Settings.ACTION_SETTINGS
        return try {
            go(ctx, Intent(action)); null
        } catch (e: Exception) {
            go(ctx, Intent(Settings.ACTION_SETTINGS)); null
        }
    }

    // ---- Apps, media, calls ----

    private fun findApp(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = name.lowercase().trim()
        if (q.isEmpty()) return null
        val apps = pm.queryIntentActivities(main, 0).map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString().lowercase()) }
        return (apps.firstOrNull { it.second == q }
            ?: apps.firstOrNull { it.second.contains(q) || (it.second.length >= 4 && q.contains(it.second)) })?.first
    }

    fun hasApp(ctx: Context, name: String) = findApp(ctx, name) != null

    fun installedApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() }.distinct().sorted().take(120)
    }

    /** Music apps that can play a song by name: (package, label). */
    fun musicApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return try {
            pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
                .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun openApp(ctx: Context, name: String): String? {
        val pkg = findApp(ctx, name) ?: return "I could not find an app called '$name'"
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "'$name' could not be opened"
        go(ctx, i); return null
    }

    private fun youtube(ctx: Context, query: String): String? {
        try {
            go(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query))
        } catch (e: Exception) {
            go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(query))))
        }
        return null
    }

    /** Plays a song by name in the preferred (or any) music app. Falls back to YouTube search. */
    private fun music(ctx: Context, query: String): String? {
        if (query.isBlank()) return media(ctx, "play_pause")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        if (Shared.musicApp.isNotBlank()) i.setPackage(Shared.musicApp)
        return try {
            go(ctx, i); null
        } catch (e: Exception) {
            youtube(ctx, query)
        }
    }

    private fun gallery(ctx: Context): String? {
        return try {
            go(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)); null
        } catch (e: Exception) {
            try {
                go(ctx, Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)); null
            } catch (e2: Exception) {
                "No gallery app found"
            }
        }
    }

    private fun camera(ctx: Context, mode: String): String? {
        val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val i = Intent(action)
        if (mode == "selfie") i.putExtra("android.intent.extras.CAMERA_FACING", 1)
        return try {
            go(ctx, i); null
        } catch (e: Exception) {
            openApp(ctx, "camera")
        }
    }

    private fun maps(ctx: Context, query: String, navigate: Boolean): String? {
        if (query.isBlank()) return openApp(ctx, "maps")
        val uri = if (navigate) Uri.parse("google.navigation:q=" + enc(query)) else Uri.parse("geo:0,0?q=" + enc(query))
        return try {
            go(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")); null
        } catch (e: Exception) {
            try {
                go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + enc(query)))); null
            } catch (e2: Exception) {
                "No maps app found"
            }
        }
    }

    private fun media(ctx: Context, key: String): String? {
        val code = when (key) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    /** Uses the back camera's flash. */
    private fun torch(ctx: Context, on: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        fun hasFlash(id: String) = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val id = cm.cameraIdList.firstOrNull {
            hasFlash(it) && cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { hasFlash(it) } ?: return "This phone has no flashlight"
        cm.setTorchMode(id, on)
        return null
    }

    fun hasFlashlight(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        false
    }

    // ---- Calls, WhatsApp, SMS (the confirmation steps live in the ViewModel) ----

    /** direct = true dials immediately when the Phone permission is granted; otherwise opens the dialer. */
    fun dial(ctx: Context, number: String, direct: Boolean): String? {
        if (number.isBlank()) return "Who should I call?"
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return try {
            if (direct && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                go(ctx, Intent(Intent.ACTION_CALL, uri)); null
            } else {
                go(ctx, Intent(Intent.ACTION_DIAL, uri))
                if (direct) "To dial automatically, allow Phone calls in Settings > Permissions. The dialer is open for now." else null
            }
        } catch (e: Exception) {
            "Could not start the call: ${e.message}"
        }
    }

    fun whatsappOpen(ctx: Context, number: String, text: String): String? {
        val digits = number.filter { it.isDigit() }
        val full = if (digits.length == 10) "91$digits" else digits
        val query = if (text.isNotBlank()) "?text=" + enc(text).replace("+", "%20") else ""
        val uri = Uri.parse("https://wa.me/$full$query")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                go(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
                return null
            } catch (e: Exception) {
                // try the next WhatsApp package
            }
        }
        return "WhatsApp is not installed on this phone"
    }

    fun smsOpen(ctx: Context, number: String, text: String): String? {
        return try {
            go(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text)); null
        } catch (e: Exception) {
            "No messaging app found"
        }
    }
}
