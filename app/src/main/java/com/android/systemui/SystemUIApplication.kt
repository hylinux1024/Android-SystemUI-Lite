package com.android.systemui

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import com.android.systemui.navigationbar.NavigationBarManager
import com.android.systemui.notification.NotificationShadeManager
import com.android.systemui.qs.QSPanelController
import com.android.systemui.statusbar.StatusBarManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SystemUIApplication : Application() {

    companion object {
        private const val TAG = "SystemUIApplication"
    }

    @Inject lateinit var statusBarManager: StatusBarManager
    @Inject lateinit var navigationBarManager: NavigationBarManager
    @Inject lateinit var notificationShadeManager: NotificationShadeManager
    @Inject lateinit var qsPanelController: QSPanelController
    @Inject lateinit var coreStartableComponent: CoreStartableComponent

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SystemUI-Lite initializing")

        // Register all CoreStartable components
        coreStartableComponent.register(statusBarManager)
        coreStartableComponent.register(navigationBarManager)
        coreStartableComponent.register(notificationShadeManager)
        coreStartableComponent.register(qsPanelController)

        // Start all components
        coreStartableComponent.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        coreStartableComponent.onConfigChanged(newConfig)
    }
}
