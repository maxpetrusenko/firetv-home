package app.firetvhome

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Read-only listener: never cancels or alters notifications. */
class NotifService : NotificationListenerService() {
    class Item(val app: String, val title: String, val text: String)

    override fun onListenerConnected() { instance = this; ping() }
    override fun onListenerDisconnected() { if (instance === this) instance = null; ping() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = ping()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = ping()

    private fun ping() { try { onChange?.invoke() } catch (e: Exception) { } }

    private fun items(max: Int): List<Item> {
        val all = try { activeNotifications } catch (e: Exception) { null } ?: return emptyList()
        val pm = packageManager
        return all.filter {
            it != null && it.packageName != packageName && !it.isOngoing &&
                (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.sortedByDescending { it.postTime }.mapNotNull { sbn ->
            try {
                val ex = sbn.notification.extras
                val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString().orEmpty()
                if (title.isEmpty() && text.isEmpty()) null
                else {
                    val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
                    Item(app, title, text)
                }
            } catch (e: Exception) { null }
        }.take(max)
    }

    companion object {
        @Volatile var instance: NotifService? = null
        @Volatile var onChange: (() -> Unit)? = null
        val connected get() = instance != null
        fun recent(max: Int): List<Item> = instance?.items(max) ?: emptyList()
    }
}
