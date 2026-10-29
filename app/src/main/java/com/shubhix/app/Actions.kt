package com.shubhix.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/** Executes the actions Gemini returns. Each returns null on success or a short problem note. */
object Actions {

    suspend fun runAll(ctx: Context, list: List<JSONObject>): String? {
        val notes = mutableListOf<String>()
        for (a in list) {
            val wait = a.optLong("wait_ms", 0)
            if (wait > 0) delay(wait)
            run(ctx, a)?.let { notes.add(it) }
        }
        return notes.joinToString("; ").ifEmpty { null }
    }

    private fun go(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private const val NEED_ACC = "Accessibility service on karo (Settings > Accessibility > Shubhix)"

    private fun run(ctx: Context, a: JSONObject): String? = try {
        when (a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "play_youtube" -> youtube(ctx, a.optString("query"))
            "media" -> media(ctx, a.optString("key"))
            "web_search" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(a.optString("query"))))); null }
            "open_url" -> { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url")))); null }
            "call" -> call(ctx, a.optString("target"))
            "flashlight" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList.first(), a.optBoolean("on", true)); null
            }
            "timer" -> {
                go(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
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
            "tap_text", "tap_screen", "scroll", "type_text", "back", "home", "recents" -> screen(a)
            else -> null
        }
    } catch (e: Exception) {
        "Action fail: ${e.message}"
    }

    private fun screen(a: JSONObject): String? {
        val s = ShubhixAccessibilityService.instance ?: return NEED_ACC
        val ok = when (a.optString("type")) {
            "tap_text" -> s.clickText(a.optString("text"))
            "tap_screen" -> s.tapPercent(a.optDouble("x", 50.0).toFloat(), a.optDouble("y", 50.0).toFloat())
            "scroll" -> s.scroll(a.optString("direction", "down"))
            "type_text" -> s.typeText(a.optString("text"))
            "back" -> s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            else -> s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        return if (ok) null else "Screen action nahi ho paya"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    fun canCall(ctx: Context, target: String) =
        !target.any { it.isLetter() } || lookup(ctx, target) != null

    private fun openApp(ctx: Context, name: String): String? {
        val pkg = findApp(ctx, name) ?: return "'$name' app nahi mila"
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "'$name' open nahi hua"
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

    private fun call(ctx: Context, target: String): String? {
        val number = if (target.any { it.isLetter() }) lookup(ctx, target) ?: return "Contact '$target' nahi mila" else target
        // ACTION_DIAL opens the dialer with the number filled; you press the green button.
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
