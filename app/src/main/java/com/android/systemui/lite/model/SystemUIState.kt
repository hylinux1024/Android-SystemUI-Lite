package com.android.systemui.lite.model

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color

/**
 * NotificationItem - Data model for a notification.
 *
 * This model is used by both:
 * 1. The legacy simulation (with hardcoded mock data)
 * 2. The real NotificationListenerService (with real system notifications)
 */
data class NotificationItem(
    // Unique identifier (legacy: Int id, real: String key like "com.android.systemui.lite.app_123_1234567890")
    val id: Any,

    // App information
    val appName: String = "",
    val packageName: String = "",

    // Notification content
    val title: String = "",
    val text: String = "",
    val content: String = "",

    // Timestamp
    val timestamp: String = "",
    val timestampMillis: Long = 0L,

    // Notification type
    val type: NotificationType = NotificationType.GENERIC,

    // Icon (for real notifications)
    val appIcon: Drawable? = null,

    // Music/media specific
    val isPlaying: Boolean = false,
    val artist: String = "",
    val trackTitle: String = "",

    // State flags
    val isRead: Boolean = false,
    val isDismissed: Boolean = false
) {
    companion object {
        /**
         * Create a NotificationItem from legacy simulation data.
         */
        fun createLegacy(
            id: Int,
            appName: String,
            title: String,
            text: String,
            timestamp: String,
            type: NotificationType,
            isPlaying: Boolean = false,
            artist: String = "",
            trackTitle: String = ""
        ) = NotificationItem(
            id = id,
            appName = appName,
            title = title,
            text = text,
            content = text,
            timestamp = timestamp,
            type = type,
            isPlaying = isPlaying,
            artist = artist,
            trackTitle = trackTitle
        )
    }

    /**
     * Get display title (handles both legacy and real notification formats).
     */
    fun getDisplayTitle(): String = title.ifEmpty { appName }

    /**
     * Get display text (handles both legacy and real notification formats).
     */
    fun getDisplayText(): String = text.ifEmpty { content }

    /**
     * Get display timestamp.
     */
    fun getDisplayTimestamp(): String {
        if (timestamp.isNotEmpty()) return timestamp
        if (timestampMillis > 0) {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestampMillis))
        }
        return ""
    }
}

enum class NotificationType {
    GENERIC,
    MESSAGE,
    EMAIL,
    MUSIC,
    SYSTEM_ALERT,
    CALL,
    ALERT,
    DOWNLOAD,
    INFO
}

data class WallpaperItem(
    val id: Int,
    val name: String,
    val colors: List<Color>,
    val isDark: Boolean = true
)

enum class ClockPosition {
    LEFT,
    CENTER,
    RIGHT
}

enum class BatteryPercentageStyle {
    ICON_ONLY,
    ICON_AND_TEXT,
    TEXT_ONLY,
    HIDDEN
}

enum class NavigationMode {
    THREE_BUTTON,
    GESTURES
}

enum class QSPanelTab {
    TILES,
    MEDIA,
    INFO
}
