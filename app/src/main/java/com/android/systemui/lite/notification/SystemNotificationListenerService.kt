package com.android.systemui.lite.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.android.systemui.lite.data.NotificationProvider
import com.android.systemui.lite.model.NotificationItem
import org.koin.core.context.GlobalContext

/**
 * SystemNotificationListenerService - Real notification listener.
 *
 * Receives notifications from the system and converts them to our
 * NotificationItem model, publishing through NotificationProvider.
 *
 * In AOSP, this role is filled by:
 * - NotificationListener (extends NotificationListenerWithPlugins > NotificationListenerService)
 * - NotifCollection / ShadeListBuilder / RenderStageManager pipeline
 */
class SystemNotificationListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "SystemNotificationListener"
    }

    private val notificationProvider by lazy {
        GlobalContext.get().get<NotificationProvider>()
    }

    private val qsm by lazy {
        GlobalContext.get().get<com.android.systemui.lite.qs.QSTileManager>()
    }

    private val appNotificationManager by lazy {
        getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
    }

    /**
     * True when the platform's current interruption filter is anything but ALL — i.e.
     * DND is active at some level (PRIORITY, ALARMS, or NONE). Callers use this to
     * suppress surface-level alerts without actually cancelling the underlying
     * notification (US-007 AC2).
     */
    private fun isDndSuppressingNotifications(): Boolean {
        val filter = appNotificationManager?.currentInterruptionFilter
            ?: return false
        return filter != NotificationListenerService.INTERRUPTION_FILTER_ALL
    }

    // Internal notification tracking
    private val activeNotifications = linkedMapOf<String, NotificationItem>()

    override fun onCreate() {
        super.onCreate()
        notificationProvider.appendLog("onCreate() - package=$packageName uid=${android.os.Process.myUid()}")
        notificationProvider.listenerCallbacks = object : NotificationProvider.ListenerCallbacks {
            override fun cancelNotification(key: String) = this@SystemNotificationListenerService.cancelNotification(key)
        }
        registerAsSystemService()
    }

    private fun registerAsSystemService() {
        notificationProvider.appendLog("registerAsSystemService() attempting...")
        try {
            val componentName = ComponentName(packageName, javaClass.canonicalName!!)
            val method = NotificationListenerService::class.java.getDeclaredMethod(
                "registerAsSystemService",
                android.content.Context::class.java,
                ComponentName::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(this, this, componentName, -1 /* UserHandle.USER_ALL */)
            notificationProvider.appendLog("registerAsSystemService() SUCCESS")
        } catch (e: Exception) {
            notificationProvider.appendLogError("registerAsSystemService() FAILED", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        notificationProvider.appendLog("onBind()")
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationProvider.appendLog("onStartCommand() flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        notificationProvider.appendLog("onListenerConnected()")
        notificationProvider.setConnected(true)

        try {
            val existing = getActiveNotifications()
            notificationProvider.appendLog("onListenerConnected() - system notificationProviderrts ${existing.size} active")
            for (sbn in existing) {
                val item = convertToNotificationItem(sbn)
                if (item != null) {
                    activeNotifications[sbn.key] = item
                }
            }
            updateNotificationList()
            notificationProvider.appendLog("onListenerConnected() - loaded ${activeNotifications.size}")
        } catch (e: Exception) {
            notificationProvider.appendLogError("onListenerConnected() error", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        notificationProvider.appendLog("onListenerDisconnected()")
        notificationProvider.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = sbn.key
        notificationProvider.setConnected(true)

        if (sbn.packageName == packageName) return
        if (sbn.packageName == "com.android.systemui") return

        // US-007 AC2 — when DND is ON we suppress visible alerts in our shade. We
        // read the live interruption filter rather than our own cached StateFlow so
        // we honor changes made elsewhere (e.g. lock screen, system UI, bubbles).
        if (isDndSuppressingNotifications()) {
            notificationProvider.appendLog("onNotificationPosted: suppressed by DND — pkg=${sbn.packageName}")
            return
        }

        try {
            val item = convertToNotificationItem(sbn)
            if (item != null) {
                activeNotifications[key] = item
                updateNotificationList()
                notificationProvider.appendLog("onNotificationPosted: '${item.title}' pkg=${sbn.packageName} total=${activeNotifications.size}")
            }
        } catch (e: Exception) {
            notificationProvider.appendLogError("onNotificationPosted error", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        activeNotifications.remove(sbn.key)
        updateNotificationList()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        activeNotifications.remove(sbn.key)
        updateNotificationList()
    }

    private fun convertToNotificationItem(sbn: StatusBarNotification): NotificationItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isEmpty() && content.isEmpty()) return null

        val type = when (notification.category) {
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_EMAIL,
            Notification.CATEGORY_SOCIAL -> com.android.systemui.lite.model.NotificationType.MESSAGE
            Notification.CATEGORY_CALL -> com.android.systemui.lite.model.NotificationType.CALL
            Notification.CATEGORY_ALARM -> com.android.systemui.lite.model.NotificationType.ALERT
            Notification.CATEGORY_TRANSPORT -> com.android.systemui.lite.model.NotificationType.DOWNLOAD
            else -> com.android.systemui.lite.model.NotificationType.INFO
        }

        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (e: Exception) {
            null
        }

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(sbn.postTime))

        return NotificationItem(
            id = sbn.key,
            appName = appLabel,
            title = title,
            text = content,
            content = content,
            timestamp = timeStr,
            appIcon = icon,
            packageName = sbn.packageName,
            timestampMillis = sbn.postTime,
            type = type,
            isRead = false,
            isDismissed = false,
            isClearable = sbn.isClearable,
            autoCancel = (notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0,
            contentIntent = notification.contentIntent
        )
    }

    private fun updateNotificationList() {
        val sorted = activeNotifications.values.sortedByDescending { it.timestampMillis }
        notificationProvider.updateNotifications(sorted)
    }

    override fun onDestroy() {
        notificationProvider.listenerCallbacks = null
        super.onDestroy()
    }
}
