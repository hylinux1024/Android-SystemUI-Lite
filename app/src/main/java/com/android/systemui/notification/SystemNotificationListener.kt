package com.android.systemui.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SystemNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SystemNotifListener"
    }

    @Inject
    lateinit var notificationShadeManager: NotificationShadeManager

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val packageName = sbn.packageName

            if (title.isEmpty() && content.isEmpty()) return

            val icon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            val entry = NotificationShadeManager.NotificationEntry(
                packageName = packageName,
                title = title,
                content = content,
                icon = icon,
                timestamp = sbn.notification.`when`.takeIf { it > 0 } ?: System.currentTimeMillis()
            )

            notificationShadeManager.addNotification(entry)
            Log.d(TAG, "Notification posted: $packageName - $title")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification post", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            notificationShadeManager.removeNotification(sbn.packageName)
            Log.d(TAG, "Notification removed: ${sbn.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification removal", e)
        }
    }
}
