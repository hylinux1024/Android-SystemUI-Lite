package com.android.systemui.lite.data

import android.util.Log
import com.android.systemui.lite.model.NotificationItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NotificationProvider — Single source of truth for notification data.
 *
 * Both SystemNotificationListenerService and ShadeCoreStartable inject this
 * via Koin, eliminating the need for static companion object StateFlows.
 */
class NotificationProvider {

    companion object {
        private const val TAG = "NotificationProvider"
    }

    // --- Notification list ---
    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    // --- Active count ---
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    // --- Listener connection state ---
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // --- Diagnostic debug log ---
    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    // --- Back-reference to the listener service for dismiss/clear ---
    interface ListenerCallbacks {
        fun cancelNotification(key: String)
    }

    @Volatile
    var listenerCallbacks: ListenerCallbacks? = null

    // --- Write methods (called by SystemNotificationListenerService) ---

    fun updateNotifications(list: List<NotificationItem>) {
        _notifications.value = list
        _activeCount.value = list.size
        Log.d(TAG, "Notifications updated: ${list.size} items")
    }

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        Log.d(TAG, "Listener connected: $connected")
    }

    fun appendLog(msg: String) {
        Log.d(TAG, msg)
        val current = _debugLog.value.toMutableList()
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        current.add("[$ts] $msg")
        if (current.size > 50) current.removeAt(0)
        _debugLog.value = current
    }

    fun appendLogError(msg: String, e: Exception? = null) {
        Log.e(TAG, msg, e)
        val current = _debugLog.value.toMutableList()
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val suffix = if (e != null) " | ${e.javaClass.simpleName}: ${e.message}" else ""
        current.add("[$ts] ERROR: $msg$suffix")
        if (current.size > 50) current.removeAt(0)
        _debugLog.value = current
    }

    // --- Dismiss actions (called by ShadeCoreStartable) ---

    fun dismissNotification(key: String) {
        listenerCallbacks?.cancelNotification(key)
    }

    fun clearAllNotifications() {
        val callbacks = listenerCallbacks ?: return
        val clearable = _notifications.value.filter { it.isClearable }
        Log.d(TAG, "clearAllNotifications: clearing ${clearable.size} of ${_notifications.value.size}")
        clearable.forEach { callbacks.cancelNotification(it.id.toString()) }
    }
}
