package com.android.systemui.lite.systemui.notification

import android.app.Notification
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.lite.SystemUIApplication
import com.android.systemui.lite.systemui.model.NotificationItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SystemNotificationListenerService - Real notification listener.
 *
 * This service receives notifications from the system and converts them
 * to our NotificationItem model for display in the notification shade.
 *
 * In AOSP, this is part of the NotifPipeline which includes:
 * - NotifCollection: Collects notifications from NotificationListenerService
 * - ShadeListBuilder: Sorts and filters notifications for display
 * - NotifViewManager: Binds notifications to views
 *
 * This is a simplified version that:
 * 1. Receives notifications via NotificationListenerService callbacks
 * 2. Converts StatusBarNotification to NotificationItem
 * 3. Provides the notification list via StateFlow for the UI layer
 */
class SystemNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "SystemNotificationListener"

        // Notification list exposed to the UI layer
        private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
        val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

        // Active notification count
        private val _activeNotificationCount = MutableStateFlow(0)
        val activeNotificationCount: StateFlow<Int> = _activeNotificationCount.asStateFlow()

        // Whether the listener is connected
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    // Internal notification tracking
    private val activeNotifications = mutableMapOf<String, NotificationItem>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SystemNotificationListenerService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListenerService connected")
        _isConnected.value = true

        // Get all active notifications
        try {
            val activeNotifs = activeNotifications
            if (activeNotifs.isNotEmpty()) {
                Log.d(TAG, "Found ${activeNotifs.size} active notifications")
                // Process existing notifications
                _notifications.value = activeNotifs.values.toList()
                _activeNotificationCount.value = activeNotifs.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active notifications: ${e.message}", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListenerService disconnected")
        _isConnected.value = false
    }

    /**
     * Called when a notification is posted.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = sbn.key
        Log.d(TAG, "Notification posted: key=${key}, pkg=${sbn.packageName}, id=${sbn.id}")

        // Filter out our own notifications
        if (sbn.packageName == packageName) {
            Log.d(TAG, "Ignoring own notification")
            return
        }

        // Filter out system UI notifications from other users
        if (sbn.packageName == "com.android.systemui") {
            return
        }

        try {
            val item = convertToNotificationItem(sbn)
            if (item != null) {
                activeNotifications[key] = item
                updateNotificationList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}", e)
        }
    }

    /**
     * Called when a notification is removed.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        Log.d(TAG, "Notification removed: key=${key}, pkg=${sbn.packageName}")

        activeNotifications.remove(key)
        updateNotificationList()
    }

    /**
     * Called when a notification is removed with ranking info.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val key = sbn.key
        Log.d(TAG, "Notification removed: key=${key}, reason=$reason")

        activeNotifications.remove(key)
        updateNotificationList()
    }

    /**
     * Convert a StatusBarNotification to our NotificationItem model.
     */
    private fun convertToNotificationItem(sbn: StatusBarNotification): NotificationItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        // Extract title and content
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip notifications without content
        if (title.isEmpty() && content.isEmpty()) {
            return null
        }

        // Determine notification type based on category
        val type = when (notification.category) {
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_EMAIL,
            Notification.CATEGORY_SOCIAL -> com.android.systemui.lite.systemui.model.NotificationType.MESSAGE

            Notification.CATEGORY_CALL -> com.android.systemui.lite.systemui.model.NotificationType.CALL
            Notification.CATEGORY_ALARM -> com.android.systemui.lite.systemui.model.NotificationType.ALERT
            Notification.CATEGORY_TRANSPORT -> com.android.systemui.lite.systemui.model.NotificationType.DOWNLOAD
            else -> com.android.systemui.lite.systemui.model.NotificationType.INFO
        }

        // Get app icon
        val icon = try {
            val appIcon = packageManager.getApplicationIcon(sbn.packageName)
            appIcon
        } catch (e: Exception) {
            null
        }

        // Get large icon if available
        val largeIcon = extras.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_LARGE_ICON)

        return NotificationItem(
            id = sbn.key,
            title = title,
            content = content,
            appIcon = icon,
            packageName = sbn.packageName,
            timestampMillis = sbn.postTime,
            type = type,
            isRead = false,
            isDismissed = false
        )
    }

    /**
     * Update the notification list and notify listeners.
     */
    private fun updateNotificationList() {
        val sortedNotifications = activeNotifications.values.sortedByDescending { it.timestamp }
        _notifications.value = sortedNotifications
        _activeNotificationCount.value = sortedNotifications.size

        Log.d(TAG, "Notification list updated: ${sortedNotifications.size} notifications")
    }

    /**
     * Dismiss a notification.
     */
    fun dismissNotification(notificationId: String) {
        val parts = notificationId.split("_")
        if (parts.size >= 2) {
            val packageName = parts[0]
            val id = parts[1].toIntOrNull()
            if (id != null) {
                try {
                    cancelNotification(packageName, null, id)
                    activeNotifications.remove(notificationId)
                    updateNotificationList()
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing notification: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Clear all notifications.
     */
    fun clearAllNotifications() {
        try {
            cancelAllNotifications()
            activeNotifications.clear()
            updateNotificationList()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing notifications: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SystemNotificationListenerService destroyed")
    }
}
