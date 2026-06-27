package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * SystemUIService - The main entry point for SystemUI.
 *
 * This service is in the com.android.systemui package to match AOSP's
 * SystemUIService package name exactly. This is important because:
 * 1. system_server looks for this specific package when binding
 * 2. The service must match the AOSP interface for compatibility
 *
 * In AOSP, system_server binds to this service after the SystemUI process starts.
 * The service calls startServicesIfNeeded() on the Application to bootstrap all
 * CoreStartable components.
 */
class SystemUIService : Service() {

    companion object {
        private const val TAG = "SystemUIService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "SystemUIService onBind")
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SystemUIService onCreate")

        // Notify the application that the system service has been created
        // Use the lite package's SystemUIApplication
        val app = application as? com.android.systemui.lite.SystemUIApplication
        if (app != null) {
            Log.d(TAG, "Calling startServicesIfNeeded on SystemUIApplication")
            app.startServicesIfNeeded()
        } else {
            Log.e(TAG, "Application is not SystemUIApplication, cannot start services")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SystemUIService onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SystemUIService onDestroy")
    }
}
