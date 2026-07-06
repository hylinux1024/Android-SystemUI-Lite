package com.android.systemui

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Required by Hilt: because SystemUIService is an @AndroidEntryPoint, it must be
 * instantiated via this factory so Hilt can perform its DI. We just log and
 * chain to super (which does the real instantiation).
 */
class SystemUIAppComponentFactory : AppComponentFactory() {

    companion object {
        private const val TAG = "SystemUIAppCompFactory"
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Log.i(TAG, "Instantiating application: $className")
        return super.instantiateApplication(cl, className)
    }

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Activity {
        Log.i(TAG, "Instantiating activity: $className")
        return super.instantiateActivity(cl, className, intent)
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        Log.i(TAG, "Instantiating service: $className")
        return super.instantiateService(cl, className, intent)
    }

    override fun instantiateReceiver(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): BroadcastReceiver {
        Log.i(TAG, "Instantiating receiver: $className")
        return super.instantiateReceiver(cl, className, intent)
    }
}
