package com.android.systemui.lite.systemui.statusbar

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

/**
 * CommandQueue - The IPC bridge between system_server and SystemUI.
 *
 * In AOSP, CommandQueue extends IStatusBar.Stub (AIDL-generated binder).
 * system_server calls methods on this binder to send commands to SystemUI.
 *
 * Since we can't use AIDL in a Gradle project without framework sources,
 * we use reflection to create an IStatusBar.Stub proxy and register it
 * with StatusBarManagerService.
 *
 * The key flow:
 * 1. Create an IStatusBar.Stub instance via reflection
 * 2. Register it with IStatusBarService.registerStatusBar()
 * 3. Receive callbacks on the stub when system_server sends commands
 */
class CommandQueue {

    companion object {
        private const val TAG = "CommandQueue"
    }

    /**
     * Callback interface for status bar commands.
     * Components implement this to receive system_server notifications.
     */
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
        fun onBiometricRunningStateChanged(running: Boolean) {}
        fun showToast(displayId: Int, token: Any?, text: CharSequence, windowToken: Any?) {}
        fun onAlertStateChanged(alertState: Int) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<Callbacks>()

    // The IStatusBar.Stub instance (created via reflection)
    private var statusBarBinderInstance: Any? = null

    // The IStatusBarService proxy
    private var statusBarServiceProxy: Any? = null

    /**
     * Register a callback receiver.
     */
    fun addCallback(callback: Callbacks) {
        synchronized(callbacks) {
            if (!callbacks.contains(callback)) {
                callbacks.add(callback)
            }
        }
    }

    /**
     * Remove a callback receiver.
     */
    fun removeCallback(callback: Callbacks) {
        synchronized(callbacks) {
            callbacks.remove(callback)
        }
    }

    /**
     * Register with StatusBarManagerService via reflection.
     *
     * This creates an IStatusBar.Stub proxy and calls:
     *   IStatusBarService.registerStatusBar(IStatusBar)
     *
     * @return true if registration succeeded
     */
    fun register(): Boolean {
        try {
            // Get IStatusBarService
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val sbBinder = getServiceMethod.invoke(null, "statusbar")
                ?: return logAndReturnFalse("Failed to get StatusBarManagerService")

            // Get IStatusBarService.Stub.asInterface
            val stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            statusBarServiceProxy = asInterfaceMethod.invoke(null, sbBinder)
                ?: return logAndReturnFalse("Failed to get IStatusBarService interface")

            // Create our IStatusBar.Stub callback
            val iStatusBarStub = createStatusBarBinder()
            if (iStatusBarStub == null) {
                Log.w(TAG, "Could not create IStatusBar.Stub, running in standalone mode")
                return false
            }

            statusBarBinderInstance = iStatusBarStub

            // Call registerStatusBar(IStatusBar)
            val registerMethod = statusBarServiceProxy!!.javaClass.getMethod(
                "registerStatusBar",
                Class.forName("com.android.internal.statusbar.IStatusBar")
            )
            val result = registerMethod.invoke(statusBarServiceProxy, iStatusBarStub)
            Log.d(TAG, "Successfully registered with StatusBarManagerService")
            return true

        } catch (e: Exception) {
            Log.w(TAG, "Registration with StatusBarManagerService failed: ${e.message}")
            Log.d(TAG, "Running in standalone mode (not registered with system_server)")
            return false
        }
    }

    /**
     * Create an IStatusBar.Stub instance via reflection.
     *
     * Note: IStatusBar.Stub is a class (not interface), so Proxy.newProxyInstance
     * won't work. We log the error and return null to run in standalone mode.
     * In standalone mode, we don't register with system_server but still function
     * as a status bar with the TYPE_STATUS_BAR window.
     */
    private fun createStatusBarBinder(): Any? {
        Log.w(TAG, "IStatusBar.Stub is a class, not an interface - cannot use Proxy")
        Log.d(TAG, "Running in standalone mode without system_server registration")
        return null
    }

    /**
     * Handle method calls from system_server on our IStatusBar binder.
     */
    private fun handleStatusBarMethod(method: Method, args: Array<Any>?): Any? {
        val methodName = method.name
        Log.d(TAG, "system_server call: $methodName")

        mainHandler.post {
            synchronized(callbacks) { callbacks.toList() }.forEach { callback ->
                try {
                    dispatchMethod(callback, methodName, args)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching $methodName to callback: ${e.message}", e)
                }
            }
        }

        return null
    }

    /**
     * Dispatch a method call to the appropriate callback.
     */
    private fun dispatchMethod(callback: Callbacks, methodName: String, args: Array<Any>?) {
        when (methodName) {
            "setIcon" -> {
                // setIcon(String slot, String pkg, int iconId, String contentDescription, int tint)
                args?.let {
                    if (it.size >= 5) {
                        callback.setIcon(
                            it[0] as String,
                            it[1] as String,
                            it[2] as Int,
                            it[3] as String,
                            it[4] as Int
                        )
                    }
                }
            }
            "removeIcon" -> {
                args?.let {
                    if (it.isNotEmpty()) {
                        callback.removeIcon(it[0] as String)
                    }
                }
            }
            "disable" -> {
                args?.let {
                    if (it.size >= 4) {
                        callback.disable(
                            it[0] as Int,
                            it[1] as Int,
                            it[2] as Int,
                            it[3] as Boolean
                        )
                    }
                }
            }
            "animateExpandNotificationsPanel" -> {
                callback.animateExpandNotificationsPanel()
            }
            "animateCollapsePanels" -> {
                args?.let {
                    if (it.size >= 2) {
                        callback.animateCollapsePanels(it[0] as Int, it[1] as Boolean)
                    }
                }
            }
            "togglePanel" -> {
                callback.togglePanel()
            }
            "animateExpandSettingsPanel" -> {
                args?.let {
                    if (it.isNotEmpty()) {
                        callback.animateExpandSettingsPanel(it[0] as? String)
                    }
                }
            }
            "setWindowState" -> {
                args?.let {
                    if (it.size >= 3) {
                        callback.setWindowState(it[0] as Int, it[1] as Int, it[2] as Int)
                    }
                }
            }
            "showGlobalActionsMenu" -> {
                callback.showGlobalActionsMenu()
            }
            "showShutdownUi" -> {
                args?.let {
                    if (it.size >= 2) {
                        callback.showShutdownUi(it[0] as Boolean, it[1] as? String)
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unhandled method: $methodName")
            }
        }
    }

    /**
     * Notify all callbacks that a panel should expand.
     */
    fun animateExpandNotificationsPanel() {
        synchronized(callbacks) {
            callbacks.forEach { it.animateExpandNotificationsPanel() }
        }
    }

    /**
     * Notify all callbacks that panels should collapse.
     */
    fun animateCollapsePanels(flags: Int = 0, force: Boolean = false) {
        synchronized(callbacks) {
            callbacks.forEach { it.animateCollapsePanels(flags, force) }
        }
    }

    /**
     * Notify all callbacks that the panel should toggle.
     */
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
