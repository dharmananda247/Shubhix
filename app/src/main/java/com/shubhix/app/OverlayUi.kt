package com.shubhix.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A small yes/no card drawn over whatever app is on screen. Used to ask before the agent sends or submits anything. */
object OverlayUi {

    suspend fun confirm(ctx: Context, question: String, timeoutMs: Long = 30_000): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        val result = CompletableDeferred<Boolean>()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var shown: View? = null
        withContext(Dispatchers.Main) {
            val card = buildCard(ctx, question) { result.complete(it) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            wm.addView(card, lp)
            shown = card
        }
        val ok = withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        withContext(Dispatchers.Main) {
            try { shown?.let { wm.removeView(it) } } catch (e: Exception) { /* already gone */ }
        }
        return ok
    }

    private fun buildCard(ctx: Context, question: String, onAnswer: (Boolean) -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF14091F.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFFC084FC.toInt())
            }
        }
        val q = TextView(ctx).apply {
            text = question
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        fun pill(label: String, fill: Int, answer: Boolean) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(22).toFloat() }
            setOnClickListener { onAnswer(answer) }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(pill("No", 0xFF3F3F5A.toInt(), false))
        val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        gap.marginStart = dp(10)
        row.addView(pill("Yes, go ahead", 0xFF7C3AED.toInt(), true), gap)
        root.addView(q)
        root.addView(row)
        return root
    }
}
