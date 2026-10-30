package com.shubhix.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShubhixAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShubhixAccessibilityService? = null

        /** True when the switch is ON in Android's Accessibility settings (even if the service has not started yet). */
        fun isEnabled(ctx: Context): Boolean {
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return v.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val w get() = resources.displayMetrics.widthPixels.toFloat()
    private val h get() = resources.displayMetrics.heightPixels.toFloat()

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    fun tapPercent(xp: Float, yp: Float): Boolean {
        val x = w * xp / 100f; val y = h * yp / 100f
        return gesture(x, y, x, y, 50)
    }

    fun scroll(dir: String): Boolean = when (dir) {
        "down" -> gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)   // content moves up
        "up" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
        "left" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
        "right" -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
        else -> false
    }

    fun global(action: Int) = performGlobalAction(action)
}
