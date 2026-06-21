package com.android.systemui

import android.app.AppComponentFactory
import android.content.Context
import android.content.Intent
import android.util.Log

class SystemUIAppComponentFactory : AppComponentFactory() {

    companion object {
        private const val TAG = "SystemUIAppCompFactory"
    }

    override fun instantiateApplication(cl: ClassLoader?, className: String?): android.app.Application {
        Log.i(TAG, "Instantiating application: $className")
        return super.instantiateApplication(cl, className)
    }

    override fun instantiateActivity(
        cl: ClassLoader?,
        className: String?,
        intent: Intent?
    ): android.app.Activity {
        Log.i(TAG, "Instantiating activity: $className")
        return super.instantiateActivity(cl, className, intent)
    }

    override fun instantiateService(
        cl: ClassLoader?,
        className: String?,
        intent: Intent?
    ): android.app.Service {
        Log.i(TAG, "Instantiating service: $className")
        return super.instantiateService(cl, className, intent)
    }

    override fun instantiateReceiver(
        cl: ClassLoader?,
        className: String?,
        intent: Intent?
    ): android.content.BroadcastReceiver {
        Log.i(TAG, "Instantiating receiver: $className")
        return super.instantiateReceiver(cl, className, intent)
    }
}
