package com.shubhix.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException

data class AgentResult(val ok: Boolean, val text: String)

/**
 * Multi-step control. Each round: read the screen (or look at a screenshot), ask Gemini for ONE
 * next step, do it, repeat. This is how tasks that span several screens get done.
 */
object Agent {
    private const val MAX_STEPS = 14

    @Volatile var stop = false

    // Apps whose screens are never read or sent anywhere. The user can add more in Settings.
    private val builtIn = listOf(
        "bank", "pay", "wallet", "upi", "paisa", "password", "authenticator", "keepass", "bitwarden",
        "lastpass", "1password", "finance", "sbi", "hdfc", "icici", "axis", "kotak"
    )

    private const val SPEC = """
You control an Android phone to finish the user's GOAL. You see the current screen as a numbered list of elements:
[id] kind "label" flags. kinds: button, input, list, text, password.
Choose ONE next step and answer with ONE JSON object only: {"say":"3-6 word status","action":{...}}
Actions:
 tap{id}  type{id,text,submit}  scroll{direction:"up"|"down"|"left"|"right"}  back  home  open_app{name}  wait{ms}
 tap_xy{x,y} (percent 0-100 of the screen; ONLY when a screenshot is attached and no listed element fits)
 done{reply}   fail{reply}
Rules:
- Use the id numbers exactly as shown. After each step you will get the new screen.
- A screenshot may be attached when the element list is empty or very short. Use it to decide, and prefer tap_xy then.
- To search inside an app: tap the search icon or box, then type into the input with submit true.
- To play a video or song: after the results appear, tap the first result that matches. Use done once it is playing.
- If a popup, ad, cookie banner or permission box blocks the way, dismiss it (Skip, Not now, Close, No thanks).
- Never enter passwords, OTP codes, card or payment details. Never confirm a purchase, payment, money transfer or deletion. If the goal needs that, use fail and ask the user to do that step.
- For any step that SENDS a message, posts, submits a form or places a call, add "confirm":"short yes/no question" inside that action, for example {"type":"tap","id":7,"confirm":"Send 'hi' to Mom?"}. The user then approves it first.
- If three steps in a row make no progress, use fail and explain what blocked you.
- When the goal is complete use done with a short friendly reply in the user's language (English, Hindi or Hinglish).
"""

    private fun fail(text: String) = AgentResult(false, text)

    suspend fun run(
        ctx: Context, goal: String, s: AppSettings, apps: List<String>,
        blocked: List<String>, status: (String) -> Unit
    ): AgentResult {
        if (ShubhixAccessibilityService.instance == null) {
            return fail(
                if (ShubhixAccessibilityService.isEnabled(ctx))
                    "Screen control is turned on but stopped. Switch Shubhix AI off and on in Accessibility settings, then try again."
                else
                    "Screen control is off. Turn it on in Settings > Permissions > Screen control, then try again."
            )
        }
        stop = false
        val blockList = builtIn + blocked.map { it.lowercase().trim() }.filter { it.length >= 3 }
        val system = buildString {
            append(s.prompt()).append("\n\n").append(SPEC)
            if (apps.isNotEmpty()) append("\nINSTALLED APPS: ").append(apps.joinToString(", "))
        }
        val log = mutableListOf<String>()
        var lastKey = ""
        var repeats = 0

        for (step in 1..MAX_STEPS) {
            if (stop) return fail("Stopped.")
            val svc = ShubhixAccessibilityService.instance
                ?: return fail("Screen control stopped working (Android switched it off). Switch Shubhix AI off and on in Accessibility settings.")

            var scr = svc.readScreen()
            var tries = 0
            while (scr == null && tries < 3) { delay(700); scr = svc.readScreen(); tries++ }
            val pkg = scr?.pkg.orEmpty()
            if (blockList.any { pkg.lowercase().contains(it) })
                return fail("I don't read screens of banking, payment, password or blocked apps. Please do this one yourself.")

            // Vision fallback: the app's screen has almost nothing readable.
            var image: String? = null
            if (s.visionOn && Build.VERSION.SDK_INT >= 30 && scr != null && scr.nodes.size < 4) {
                status("$step. Looking at the screen")
                image = svc.screenshotBase64()
            }

            val user = buildString {
                append("GOAL: ").append(goal)
                append("\nSTEP: ").append(step).append(" of ").append(MAX_STEPS)
                append("\nAPP ON SCREEN: ").append(pkg.ifEmpty { "unknown" })
                if (log.isNotEmpty()) append("\nRECENT STEPS:\n").append(log.takeLast(6).joinToString("\n"))
                append("\nSCREEN:\n").append(scr?.describe() ?: "(screen could not be read)")
                if (image != null) append("\nA SCREENSHOT of the current screen is attached.")
            }

            val raw = try {
                withTimeout(25_000) { GeminiClient.oneShot(s.apiKey, s.model, system, user, image) }
            } catch (e: TimeoutCancellationException) {
                return fail("Gemini took too long to answer. Check your connection and try again.")
            } catch (e: IOException) {
                return fail("I lost the internet connection while working.")
            }

            val parsed = try {
                JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
            } catch (e: Exception) {
                log.add("step $step: unreadable answer")
                continue
            }
            val say = parsed.optString("say").ifBlank { "Working…" }
            val a = parsed.optJSONObject("action") ?: JSONObject().put("type", "fail").put("reply", "I could not decide the next step.")
            val type = a.optString("type")
            status("$step. $say")

            // Detect being stuck: the same step on the same screen three times.
            val key = type + a.optInt("id", -1) + a.optString("text") + (scr?.nodes?.size ?: 0)
            repeats = if (key == lastKey) repeats + 1 else 0
            lastKey = key
            if (repeats >= 2) return fail("I got stuck on this screen. Please try again or do the last step yourself.")

            // Ask the user first for anything that sends or submits.
            val question = a.optString("confirm")
            if (question.isNotBlank() && type != "done" && type != "fail") {
                if (!Settings.canDrawOverlays(ctx))
                    return fail("I need your OK before this step (\"$question\"), but the Display over other apps permission is off. Turn on the Floating bubble in Settings and try again.")
                status("Waiting for your OK…")
                if (!OverlayUi.confirm(ctx, question)) return fail("Okay, I stopped before this step: $question")
            }

            var note: String? = null
            when (type) {
                "done" -> return AgentResult(true, a.optString("reply").ifBlank { "Done." })
                "fail" -> return fail(a.optString("reply").ifBlank { "I could not finish that." })
                "tap" -> if (scr == null || !svc.tapNode(scr, a.optInt("id", -1))) note = "tap failed"
                "tap_xy" -> if (!svc.tapPercent(a.optDouble("x", 50.0).toFloat(), a.optDouble("y", 50.0).toFloat())) note = "tap failed"
                "type" -> note = if (scr == null) "no screen" else svc.typeInto(scr, a.optInt("id", -1), a.optString("text"), a.optBoolean("submit", false))
                "scroll" -> if (!svc.scroll(a.optString("direction", "down"))) note = "scroll failed"
                "back" -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                "open_app" -> {
                    note = Actions.runOne(ctx, JSONObject().put("type", "open_app").put("name", a.optString("name")))
                    delay(2500)
                }
                "wait" -> delay(a.optLong("ms", 1500).coerceIn(300, 5000))
                else -> note = "unknown step '$type'"
            }
            log.add(("step $step: $type " + (a.optInt("id", -1).takeIf { it >= 0 } ?: "") + " " + a.optString("text")).trim() +
                (if (note != null) " -> FAILED ($note)" else " -> ok"))
            delay(1300) // let the screen settle before looking again
        }
        return fail("I stopped after $MAX_STEPS steps without finishing. Please try again or finish it yourself.")
    }
}
