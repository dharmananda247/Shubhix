package com.shubhix.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: Talk, Camera, Gallery and WhatsApp in one tap. */
class ShubhixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.widget_shubhix)
            v.setOnClickPendingIntent(R.id.w_talk, pending(context, "talk", 1))
            v.setOnClickPendingIntent(R.id.w_camera, pending(context, "camera", 2))
            v.setOnClickPendingIntent(R.id.w_gallery, pending(context, "gallery", 3))
            v.setOnClickPendingIntent(R.id.w_whatsapp, pending(context, "whatsapp", 4))
            appWidgetManager.updateAppWidget(id, v)
        }
    }

    private fun pending(ctx: Context, cmd: String, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setAction("shubhix.widget." + cmd)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("cmd", cmd)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
