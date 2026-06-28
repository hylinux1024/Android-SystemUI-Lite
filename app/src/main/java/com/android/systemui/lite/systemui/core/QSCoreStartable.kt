package com.android.systemui.lite.systemui.core

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.android.systemui.lite.systemui.CoreStartable
import com.android.systemui.lite.systemui.qs.QSTileManager

class QSCoreStartable(private val context: Context) : CoreStartable {

    companion object {
        private const val TAG = "QSCoreStartable"
    }

    private var qsTileManager: QSTileManager? = null

    override fun start() {
        Log.d(TAG, "Starting QSCoreStartable...")
        try {
            qsTileManager = QSTileManager(context)
            qsTileManager?.start()
            Log.d(TAG, "QSCoreStartable started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QSCoreStartable: ${e.message}", e)
        }
    }

    override fun onBootCompleted() {
        Log.d(TAG, "onBootCompleted")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping QSCoreStartable...")
        qsTileManager?.stop()
        qsTileManager = null
    }

    fun getQSTileManager(): QSTileManager? = qsTileManager
}
