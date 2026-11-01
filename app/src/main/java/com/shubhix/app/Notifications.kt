package com.shubhix.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

data class NoteItem(val key: String, val app: String, val title: String, val text: String, val time: Long)

/** Recent notifications, kept in memory only (never saved, never sent anywhere unless you ask to hear them). */
object NotificationStore {
    private val items = ArrayList<NoteItem>()

    @Synchronized fun add(n: NoteItem) {
        items.removeAll { it.key == n.key }
        items.add(n)
        while (items.size > 30) items.removeAt(0)
    }

    @Synchronized fun remove(key: String) { items.removeAll { it.key == key } }
    @Synchronized fun latest(count: Int): List<NoteItem> = items.takeLast(count).reversed()
    @Synchronized fun counts(): Map<String, Int> = items.groupingBy { it.app }.eachCount()
    @Synchronized fun total(): Int = items.size

    fun hasAccess(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (e: Exception) {
        false
    }
}

class NotifyService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val n = sbn ?: return
            if (n.packageName == packageName || n.isOngoing) return
            val ex = n.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
            } catch (e: Exception) {
                n.packageName.substringAfterLast('.')
            }
            NotificationStore.add(NoteItem(n.key, app, title, text, n.postTime))
        } catch (e: Exception) {
            // a bad notification must never crash the listener
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try { sbn?.key?.let { NotificationStore.remove(it) } } catch (e: Exception) { /* ignore */ }
    }
}
