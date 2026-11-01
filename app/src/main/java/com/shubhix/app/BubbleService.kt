package com.shubhix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/** The floating avatar. Tap = talk, drag = move, long-press = open Shubhix, tap during a task = stop it. */
class BubbleService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "shubhix_bubble"
        private const val NOTIF_ID = 42

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun start(ctx: Context) {
            // A microphone foreground service may only start when the microphone permission is already granted.
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            try { ctx.startForegroundService(Intent(ctx, BubbleService::class.java)) } catch (e: Exception) { /* not allowed right now */ }
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, BubbleService::class.java)) } catch (e: Exception) { /* ignore */ }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var image: ImageView? = null
    private var disc: GradientDrawable? = null
    private var shownPersona = ""

    private val vm get() = (application as ShubhixApp).vm

    private val poll = object : Runnable {
        override fun run() {
            try {
                val s = vm.settings.value
                if (s.persona != shownPersona) {
                    shownPersona = s.persona
                    image?.setImageResource(if (s.persona == "DJ") R.drawable.avatar_dj else R.drawable.avatar_shubhu)
                }
                val color = when {
                    vm.listening -> 0xFFEF4444.toInt()      // red: listening
                    vm.agentRunning -> 0xFFF59E0B.toInt()   // amber: working on a task
                    vm.thinking -> 0xFFF59E0B.toInt()
                    else -> accentFor(s.persona).toArgb()
                }
                disc?.setColor(color)
            } catch (e: Exception) { /* keep polling */ }
            handler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startAsForeground()
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
            addBubble()
            vm.echo = { text ->
                if (!(application as ShubhixApp).visible) {
                    handler.post { Toast.makeText(applicationContext, text.take(140), Toast.LENGTH_LONG).show() }
                }
            }
            running = true
            handler.post(poll)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating bubble", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Shubhix is ready")
            .setContentText("Tap the floating avatar to talk")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun addBubble() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val d = resources.displayMetrics.density
        val size = (58 * d).toInt()
        val pad = (3 * d).toInt()

        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentFor(vm.settings.value.persona).toArgb()) }
        disc = ring
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
        }
        image = img
        val frame = FrameLayout(this).apply {
            background = ring
            alpha = 0.95f
            addView(img, FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad).apply { setMargins(pad, pad, pad, pad) })
        }
        box = frame

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = (240 * d).toInt() }

        frame.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
            var moved = false; var downTime = 0L
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                        moved = false; downTime = e.eventTime
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            lp.x = startX + dx; lp.y = startY + dy
                            try { manager.updateViewLayout(frame, lp) } catch (ex: Exception) { /* view gone */ }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            if (e.eventTime - downTime > 600) openApp() else onTap()
                        }
                        return true
                    }
                }
                return false
            }
        })
        manager.addView(frame, lp)
    }

    private fun onTap() {
        val assistant = vm
        if (assistant.agentRunning) {
            assistant.stopAgent()
            Toast.makeText(applicationContext, "Stopping…", Toast.LENGTH_SHORT).show()
        } else {
            assistant.mic()
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { vm.echo = null } catch (e: Exception) { /* ignore */ }
        try { box?.let { wm?.removeView(it) } } catch (e: Exception) { /* ignore */ }
        box = null
        super.onDestroy()
    }
}
