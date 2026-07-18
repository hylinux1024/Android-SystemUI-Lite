package com.android.systemui.statusbar.shade

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Command entry point for expanding / collapsing / toggling the notification
 * panel. Mirrors AOSP `ShadeController` in its minimal form.
 *
 * All callers (status-bar touch forward, back-press handler, future
 * CommandQueue callbacks) go through this interface rather than touching
 * [NotificationPanelViewController] directly.
 */
interface ShadeController {
    fun collapseShade(animate: Boolean = true)
    fun expandNotificationsPanel(animate: Boolean = true)
    fun togglePanel()
}

@Singleton
class ShadeControllerImpl @Inject constructor(
    private val panelController: NotificationPanelViewController
) : ShadeController {

    companion object {
        private const val TAG = "ShadeController"
    }

    override fun collapseShade(animate: Boolean) {
        Log.i(TAG, "collapseShade animate=$animate")
        panelController.collapse(animate)
    }

    override fun expandNotificationsPanel(animate: Boolean) {
        Log.i(TAG, "expandNotificationsPanel animate=$animate")
        panelController.expand(animate)
    }

    override fun togglePanel() {
        if (panelController.isFullyCollapsed()) {
            expandNotificationsPanel()
        } else {
            collapseShade()
        }
    }
}
