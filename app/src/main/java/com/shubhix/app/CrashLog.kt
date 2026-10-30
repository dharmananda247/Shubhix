package com.shubhix.app

import android.content.Context
import android.util.Log
import java.io.File

/** If the app ever crashes, the error is saved here and shown in Settings > About so it can be fixed. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { file(app).writeText(Log.getStackTraceString(e).take(6000)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String = try {
        val f = file(ctx)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}
