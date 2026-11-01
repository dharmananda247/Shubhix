package com.shubhix.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** One thing visible on screen that the agent can act on. */
data class UiNode(val id: Int, val kind: String, val label: String, val flags: String, val cx: Int, val cy: Int)

/** A numbered snapshot of the screen. Node references are only valid right after the snapshot. */
class Screen(val pkg: String, val nodes: List<UiNode>, val refs: List<AccessibilityNodeInfo>) {
    fun describe(): String =
        if (nodes.isEmpty()) "(nothing readable on screen)"
        else nodes.joinToString("\n") { "[${it.id}] ${it.kind} \"${it.label}\"${it.flags}" }
}

class ShubhixAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShubhixAccessibilityService? = null
        private const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"

        /** True when the switch is ON in Android's Accessibility settings (even if the service has not started yet). */
        fun isEnabled(ctx: Context): Boolean {
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return v.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        /**
         * Optional self-repair. Works only if the one-time permission WRITE_SECURE_SETTINGS was granted
         * (it can only be granted from a computer or wireless debugging). Otherwise it quietly does nothing.
         */
        fun tryAutoEnable(ctx: Context): Boolean {
            if (ctx.checkSelfPermission(SECURE) != PackageManager.PERMISSION_GRANTED) return false
            if (instance != null) return true
            return try {
                val cr = ctx.contentResolver
                val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val others = cur.split(':').filter { it.isNotEmpty() && !it.startsWith(ctx.packageName + "/") }
                val me = ctx.packageName + "/" + ShubhixAccessibilityService::class.java.name
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me).joinToString(":"))
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    } catch (e: Exception) { /* ignore */ }
                }, 500)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val w get() = resources.displayMetrics.widthPixels.toFloat()
    private val h get() = resources.displayMetrics.heightPixels.toFloat()

    // ---- reading the screen ----

    fun readScreen(): Screen? {
        return try {
            val root = rootInActiveWindow ?: return null
            val nodes = mutableListOf<UiNode>()
            val refs = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (nodes.size >= 70 || depth > 40 || !n.isVisibleToUser) return
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val label = listOf(text, desc).filter { it.isNotEmpty() }.distinct().joinToString(" | ").take(110)
                if (label.isNotEmpty() || n.isClickable || n.isEditable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    val kind = when {
                        n.isPassword -> "password"
                        n.isEditable -> "input"
                        n.isClickable -> "button"
                        n.isScrollable -> "list"
                        else -> "text"
                    }
                    val flags = buildString {
                        if (n.isChecked) append(" checked")
                        if (n.isScrollable) append(" scrollable")
                        if (n.isFocused) append(" focused")
                    }
                    nodes.add(UiNode(nodes.size, kind, if (n.isPassword) "(hidden)" else label, flags, r.centerX(), r.centerY()))
                    refs.add(n)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            Screen(root.packageName?.toString() ?: "", nodes, refs)
        } catch (e: Exception) {
            null
        }
    }

    // ---- acting on numbered nodes (agent mode) ----

    fun tapNode(scr: Screen, id: Int): Boolean = try {
        val ref = scr.refs.getOrNull(id)
        if (ref == null) false else {
            var c: AccessibilityNodeInfo? = ref
            var hops = 0
            while (c != null && !c.isClickable && hops < 6) { c = c.parent; hops++ }
            if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
            else tapPixel(scr.nodes[id].cx.toFloat(), scr.nodes[id].cy.toFloat())
        }
    } catch (e: Exception) { false }

    /** Returns null on success, or a short problem note. */
    fun typeInto(scr: Screen, id: Int, text: String, submit: Boolean): String? = try {
        val ref = scr.refs.getOrNull(id)
        when {
            ref == null -> "No element with that number"
            ref.isPassword -> "I never type into password fields"
            else -> {
                ref.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (!ref.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Could not type in that field"
                else {
                    if (submit && Build.VERSION.SDK_INT >= 30) {
                        ref.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                    null
                }
            }
        }
    } catch (e: Exception) { "Typing failed: ${e.message}" }

    // ---- simple actions ----

    fun clickText(text: String): Boolean = try {
        val root = rootInActiveWindow
        var done = false
        if (root != null) {
            for (n in root.findAccessibilityNodeInfosByText(text)) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; break }
            }
        }
        done
    } catch (e: Exception) { false }

    fun typeText(text: String): Boolean = try {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f == null) false else {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    } catch (e: Exception) { false }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean = try {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        dispatchGesture(g, null, null)
    } catch (e: Exception) { false }

    private fun tapPixel(x: Float, y: Float) = gesture(x, y, x, y, 50)

    fun tapPercent(xp: Float, yp: Float): Boolean = tapPixel(w * xp / 100f, h * yp / 100f)

    fun scroll(dir: String): Boolean = when (dir) {
        "down" -> gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)   // content moves up
        "up" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
        "left" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
        "right" -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
        else -> false
    }

    fun global(action: Int): Boolean = try { performGlobalAction(action) } catch (e: Exception) { false }
}
