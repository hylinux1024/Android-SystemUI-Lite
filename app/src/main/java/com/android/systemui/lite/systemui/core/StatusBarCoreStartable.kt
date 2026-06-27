package com.android.systemui.lite.systemui.core

import android.content.Context
import android.util.Log
import com.android.systemui.lite.SystemUIApplication
import com.android.systemui.lite.systemui.statusbar.StatusBarManager

/**
 * StatusBarCoreStartable - The CoreStartable for the status bar.
 *
 * In AOSP, this would be CentralSurfacesImpl (the main status bar coordinator).
 * It starts the StatusBarManager which manages:
 * - The TYPE_STATUS_BAR system window
 * - CommandQueue registration with system_server
 * - System state monitoring (battery, wifi, bluetooth, etc.)
 *
 * This class follows AOSP's CoreStartable pattern:
 * - Created by the DI graph (or manually in our case)
 * - start() is called during SystemUI bootstrap
 * - onBootCompleted() is called after all components are started
 */
class StatusBarCoreStartable(private val context: Context) {

    companion object {
        private const val TAG = "StatusBarCoreStartable"
    }

    private var statusBarManager: StatusBarManager? = null

    /**
     * Start the status bar component.
     * Called during SystemUI bootstrap from SystemUIApplication.startServicesIfNeeded().
     */
    fun start() {
        Log.d(TAG, "Starting StatusBarCoreStartable...")

        try {
            statusBarManager = StatusBarManager(context)
            statusBarManager?.start()
            Log.d(TAG, "StatusBarCoreStartable started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StatusBarCoreStartable: ${e.message}", e)
        }
    }

    /**
     * Called after all CoreStartables have been started.
     * Can be used to perform post-initialization tasks.
     */
    fun onBootCompleted() {
        Log.d(TAG, "onBootCompleted called for StatusBarCoreStartable")
        // Additional initialization after all components are ready
    }

    /**
     * Stop the status bar component.
     */
    fun stop() {
        Log.d(TAG, "Stopping StatusBarCoreStartable...")
        statusBarManager?.stop()
        statusBarManager = null
    }

    /**
     * Get the StatusBarManager instance.
     */
    fun getStatusBarManager(): StatusBarManager? = statusBarManager
}
