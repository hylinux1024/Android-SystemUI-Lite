package com.android.systemui.statusbar.shade

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * Vertical container that holds the QS placeholder above the notification stack.
 *
 * Mirrors AOSP `NotificationsQuickSettingsContainer`. Children lay out top-to-bottom;
 * clip is disabled so child animations can extend past the container bounds.
 */
class NotificationContainerParent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }
}
