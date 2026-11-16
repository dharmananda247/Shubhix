package com.shubhix.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.speech.SpeechRecognizer

data class TestRow(val label: String, val ok: Boolean, val detail: String)

/** The command test: checks how everyday phrases are understood, without performing any of them. */
object SelfTest {
    private val cases = listOf(
        Pair("torch on", "flashlight"), Pair("volume up", "volume"), Pair("what time is it", "reply"),
        Pair("battery level", "reply"), Pair("scroll down", "scroll"), Pair("go back", "back"),
        Pair("go home", "home"), Pair("recent apps", "recents"), Pair("take a screenshot", "system"),
        Pair("open gallery", "gallery"), Pair("open camera", "camera"), Pair("play arijit singh songs", "music"),
        Pair("play bollywood songs on youtube", "youtube_play"), Pair("navigate to railway station", "maps"),
        Pair("call mom", "call"), Pair("hi call rakhi and tap call", "call"),
        Pair("message mom on whatsapp saying hi", "whatsapp"), Pair("sms mom saying hello", "sms"),
        Pair("open wifi settings", "settings_page"), Pair("set a 5 minute timer", "timer"),
        Pair("pause", "media"), Pair("next song", "media")
    )

    fun run(ctx: Context): List<TestRow> {
        val out = mutableListOf<TestRow>()
        for (c in cases) {
            val r = try { LocalCommands.parse(ctx, c.first, emptyList()) } catch (e: Exception) { null }
            val actual = when {
                r == null -> "ai"
                r.agentGoal != null -> "agent"
                r.actions.isNotEmpty() -> r.actions[0].optString("type")
                else -> "reply"
            }
            val ok = actual == c.second
            out.add(TestRow("\"" + c.first + "\"", ok, if (ok) "understood as " + c.second else "expected " + c.second + " but got " + actual))
        }

        fun perm(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        out.add(TestRow("Flashlight", Actions.hasFlashlight(ctx), if (Actions.hasFlashlight(ctx)) "Found." else "This phone has no flashlight."))
        out.add(TestRow("Microphone permission", perm(Manifest.permission.RECORD_AUDIO), "Needed for voice."))
        out.add(TestRow("Contacts permission", perm(Manifest.permission.READ_CONTACTS), "Needed to find people to call."))
        out.add(TestRow("Phone calls permission", perm(Manifest.permission.CALL_PHONE), "Needed to dial automatically."))
        val speech = SpeechRecognizer.isRecognitionAvailable(ctx)
        out.add(TestRow("Speech recognition", speech, if (speech) "Available." else "Install or update the Google app."))
        out.add(TestRow("Screen control", ShubhixAccessibilityService.instance != null, "Needed for tapping, scrolling and agent mode."))
        out.add(TestRow("Display over other apps", BubbleService.canDraw(ctx), "Needed for the bubble and the Yes / No card."))
        out.add(TestRow("Notification access", NotificationStore.hasAccess(ctx), "Optional: lets me read notifications aloud."))
        val music = Actions.musicApps(ctx).size
        out.add(TestRow("Music app", music > 0, if (music > 0) "$music app(s) can play songs by name." else "No music app found; YouTube search is used."))
        val gallery = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY), 0) != null
        out.add(TestRow("Gallery app", gallery, if (gallery) "Found." else "No default gallery found."))
        return out
    }
}
