package com.android.systemui.lite.statusbar

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.util.Log
import com.android.internal.statusbar.IStatusBar
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.StatusBarIcon

/**
 * CommandQueue - The IPC bridge between system_server and SystemUI.
 *
 * Uses framework-minus-apex.jar (hidden APIs) to directly invoke
 * IStatusBarService.registerStatusBar() and implement IStatusBar.Stub,
 * eliminating all reflection from the registration path.
 *
 * Kotlin "by" delegation to IStatusBar.Default provides no-op stubs
 * for all 70+ interface methods, so we only override what we need.
 */
class CommandQueue {

    companion object {
        private const val TAG = "CommandQueue"
    }

    interface Callbacks {
        fun setIcon(slot: String, packageName: String, resourceId: Int, contentDescription: String, tint: Int) {}
        fun removeIcon(slot: String) {}
        fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {}
        fun animateExpandNotificationsPanel() {}
        fun animateCollapsePanels(flags: Int, force: Boolean) {}
        fun togglePanel() {}
        fun animateExpandSettingsPanel(obj: String?) {}
        fun setWindowState(displayId: Int, window: Int, state: Int) {}
        fun showRecentApps(triggeredFromAltTab: Boolean) {}
        fun hideRecentApps(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) {}
        fun showGlobalActionsMenu() {}
        fun showShutdownUi(isReboot: Boolean, reason: String?) {}
        fun onRotationProposal(rotation: Int, isValid: Boolean) {}
        fun onBiometricAuthenticated() {}
        fun onBiometricHelp(message: String?) {}
        fun onBiometricError(message: String?) {}
        fun showToast(displayId: Int, token: Any?, text: CharSequence, windowToken: Any?) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<Callbacks>()

    private var statusBarBinder: StatusBarStub? = null
    private var statusBarService: IStatusBarService? = null

    fun addCallback(callback: Callbacks) {
        synchronized(callbacks) {
            if (!callbacks.contains(callback)) {
                callbacks.add(callback)
            }
        }
    }

    fun removeCallback(callback: Callbacks) {
        synchronized(callbacks) {
            callbacks.remove(callback)
        }
    }

    /**
     * Register with StatusBarManagerService. No reflection.
     *
     * @return true if registration succeeded
     */
    fun register(): Boolean {
        try {
            val binder = ServiceManager.getService("statusbar")
                ?: return logAndReturnFalse("Failed to get StatusBarManagerService")

            statusBarService = IStatusBarService.Stub.asInterface(binder)
                ?: return logAndReturnFalse("Failed to get IStatusBarService interface")

            val iStatusBar = StatusBarStub()
            statusBarBinder = iStatusBar

            statusBarService!!.registerStatusBar(iStatusBar)
            Log.d(TAG, ">>> registerStatusBar() called successfully with StatusBarStub")
            Log.d(TAG, "Successfully registered with StatusBarManagerService")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Registration with StatusBarManagerService failed: ${e.message}")
            Log.d(TAG, "Running in standalone mode (not registered with system_server)")
            return false
        }
    }

    // ── IStatusBar.Stub with Kotlin delegation to Default for all no-op methods ──

    private inner class StatusBarStub : IStatusBar.Stub(), IStatusBar by IStatusBar.Default() {

        override fun asBinder(): IBinder = this

        override fun disable(displayId: Int, state1: Int, state2: Int) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.disable(displayId, state1, state2, false)
                }
            }
        }

        override fun animateExpandNotificationsPanel() {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.animateExpandNotificationsPanel()
                }
            }
        }

        override fun animateExpandSettingsPanel(subPanel: String?) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.animateExpandSettingsPanel(subPanel)
                }
            }
        }

        override fun animateCollapsePanels() {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.animateCollapsePanels(0, false)
                }
            }
        }

        override fun togglePanel() {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.togglePanel()
                }
            }
        }

        override fun setWindowState(display: Int, window: Int, state: Int) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.setWindowState(display, window, state)
                }
            }
        }

        override fun showRecentApps(triggeredFromAltTab: Boolean) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.showRecentApps(triggeredFromAltTab)
                }
            }
        }

        override fun hideRecentApps(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey)
                }
            }
        }

        override fun showGlobalActionsMenu() {
            Log.d(TAG, "<<< system_server called showGlobalActionsMenu()")
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.showGlobalActionsMenu()
                }
            }
        }

        override fun showShutdownUi(isReboot: Boolean, reason: String?) {
            Log.d(TAG, "<<< system_server called showShutdownUi(isReboot=$isReboot, reason=$reason)")
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.showShutdownUi(isReboot, reason)
                }
            }
        }

        override fun onProposedRotationChanged(rotation: Int, isValid: Boolean) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.onRotationProposal(rotation, isValid)
                }
            }
        }

        override fun onBiometricAuthenticated(modality: Int) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.onBiometricAuthenticated()
                }
            }
        }

        override fun onBiometricHelp(modality: Int, message: String?) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.onBiometricHelp(message)
                }
            }
        }

        override fun onBiometricError(modality: Int, error: Int, vendorCode: Int) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.onBiometricError("error=$error vendor=$vendorCode")
                }
            }
        }

        override fun showToast(
            uid: Int, packageName: String?, token: IBinder?, text: CharSequence?,
            windowToken: IBinder?, duration: Int,
            callback: android.app.ITransientNotificationCallback?, displayId: Int
        ) {
            mainHandler.post {
                synchronized(callbacks) { callbacks.toList() }.forEach {
                    it.showToast(displayId, token, text ?: "", windowToken)
                }
            }
        }
    }

    // ── Convenience methods (called internally from UI) ──

    fun animateExpandNotificationsPanel() {
        synchronized(callbacks) {
            callbacks.forEach { it.animateExpandNotificationsPanel() }
        }
    }

    fun animateCollapsePanels(flags: Int = 0, force: Boolean = false) {
        synchronized(callbacks) {
            callbacks.forEach { it.animateCollapsePanels(flags, force) }
        }
    }

    fun togglePanel() {
        synchronized(callbacks) {
            callbacks.forEach { it.togglePanel() }
        }
    }

    private fun logAndReturnFalse(message: String): Boolean {
        Log.w(TAG, message)
        return false
    }
}
