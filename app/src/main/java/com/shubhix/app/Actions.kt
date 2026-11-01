package com.shubhix.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/** Executes the actions that local commands or Gemini produce. Each returns null on success, or a short note. */
object Actions {

    private const val NEED_ACC = "Screen control is off. Open Shubhix Settings > Permissions and turn it on in Accessibility."
    private const val STUCK_ACC = "Screen control is turned on but not running. Switch Shubhix off and on again in Accessibility settings."

    suspend fun runAll(ctx: Context, list: List<JSONObject>): String? {
        val notes = mutableListOf<String>()
        for (a in list) {
            val wait = a.optLong("wait_ms", 0)
            if (wait > 0) delay(wait)
            run(ctx, a)?.let { notes.add(it) }
        }
        return notes.joinToString("; ").ifEmpty { null }
    }

    /** Runs a single action; used by agent mode. Returns null on success or a short note. */
    fun runOne(ctx: Context, a: JSONObject): String? = run(ctx, a)

    private fun go(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun run(ctx: Context, a: JSONObject): String? = try {
        when (a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "play_youtube" -> youtube(ctx, a.optString("query"))
            "media" -> media(ctx, a.optString("key"))
            "web_search" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(a.optString("query"))))); null }
            "open_url" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url")))); null }
            "call" -> call(ctx, a.optString("target"))
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
            "wifi" -> { go(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)); return null }
            "bluetooth" -> { go(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return null }
            "settings" -> { go(ctx, Intent(Settings.ACTION_SETTINGS)); return null }
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

    // ---- Apps, media, calls ----

    private fun findApp(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = name.lowercase().trim()
        if (q.isEmpty()) return null
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString().lowercase() }
        return (apps.firstOrNull { it.second == q }
            ?: apps.firstOrNull { it.second.contains(q) || (it.second.length >= 4 && q.contains(it.second)) })?.first
    }

    fun hasApp(ctx: Context, name: String) = findApp(ctx, name) != null

    fun installedApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() }.distinct().sorted().take(120)
    }

    fun canCall(ctx: Context, target: String) =
        !target.any { it.isLetter() } || lookup(ctx, target) != null

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

    /** Uses the back camera's flash. The old code picked the first camera, which can be the front one. */
    private fun torch(ctx: Context, on: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        fun hasFlash(id: String) = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val id = cm.cameraIdList.firstOrNull {
            hasFlash(it) && cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { hasFlash(it) } ?: return "This phone has no flashlight"
        cm.setTorchMode(id, on)
        return null
    }

    private fun call(ctx: Context, target: String): String? {
        val number = if (target.any { it.isLetter() }) lookup(ctx, target) ?: return "Contact '$target' not found (is the Contacts permission on?)" else target
        // ACTION_DIAL opens the dialer with the number filled in; you press the green button.
        go(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return null
    }

    private fun lookup(ctx: Context, name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
