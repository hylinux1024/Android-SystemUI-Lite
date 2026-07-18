package com.android.systemui.statusbar.shade

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root view of the notification-shade window — lives in TYPE_NOTIFICATION_SHADE.
 *
 * Mirrors AOSP `NotificationShadeWindowView`. The actual gesture handling is
 * delegated to a [touchHandler] installed by [NotificationPanelViewController];
 * this view is just the transport.
 */
class NotificationShadeWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Set by NotificationPanelViewController once the panel view is inflated. */
    var touchHandler: ((MotionEvent) -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler?.invoke(event) ?: false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Forward the interception decision to the panel's TouchHandler so it can
        // decide whether a vertical drag should be captured.
        return touchHandler?.invoke(event) ?: false
    }
}
