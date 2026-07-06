package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SystemUIService : Service() {

    companion object {
        private const val TAG = "SystemUIService"
    }

    override fun onCreate() {
        super.onCreate()
        // In AOSP, SystemUIService.onCreate() calls startServicesIfNeeded().
        // In this Hilt-based variant the Application.onCreate() triggers the
        // start path earlier (Android guarantees Application.onCreate runs
        // before any service is instantiated). Delegating start to the
        // Service here would re-run it; so this Service's role in Phase 1 is
        // "be present and stay alive" — the process lives because SystemUI is
        // declared android:persistent. Later phases can re-delegate start here
        // if needed.
        Log.i(TAG, "onCreate — services already started by Application")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }
}
