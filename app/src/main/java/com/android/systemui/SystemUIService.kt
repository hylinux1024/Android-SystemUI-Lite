package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SystemUIService : Service() {

    companion object {
        private const val TAG = "SystemUIService"
    }

    @Inject lateinit var coreStartableComponent: CoreStartableComponent

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SystemUIService onCreate")
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
        Log.i(TAG, "SystemUIService onDestroy")
    }
}
