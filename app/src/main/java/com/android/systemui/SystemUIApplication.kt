package com.android.systemui

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import com.android.systemui.statusbar.StatusBarManager
import com.android.systemui.statusbar.shade.NotificationPanelViewController
import com.android.systemui.statusbar.shade.NotificationShadeWindowController
import com.android.systemui.wallpapers.WallpaperProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SystemUIApplication : Application() {

    companion object {
        private const val TAG = "SystemUIApplication"
    }

    @Inject lateinit var coreStartableComponent: CoreStartableComponent
    @Inject lateinit var wallpaperProvider: WallpaperProvider
    @Inject lateinit var statusBarManager: StatusBarManager
    @Inject lateinit var shadeWindowController: NotificationShadeWindowController
    @Inject lateinit var notificationPanelController: NotificationPanelViewController

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — Hilt graph ready, registering CoreStartables")

        // WallpaperProvider: extracts WallpaperColors at boot and listens for
        // changes (scaffolding for future status-bar / monet theming).
        coreStartableComponent.register(wallpaperProvider)

        // Status bar: adds the TYPE_STATUS_BAR window overlay. Its start() runs
        // inside coreStartableComponent.start() below, per the CoreStartableComponent
        // one-shot contract (register must precede start).
        coreStartableComponent.register(statusBarManager)
        // Wire the auto-hide controller to the status bar element before start().
        statusBarManager.autoHideController.setStatusBar(statusBarManager)

        // Notification shade window (TYPE_NOTIFICATION_SHADE) — hosts the expanded
        // panel. Must be created before the panel controller binds its TouchHandler.
        coreStartableComponent.register(shadeWindowController)
        // Panel controller — binds TouchHandler to the shade window and drives
        // expand/collapse animations.
        coreStartableComponent.register(notificationPanelController)

        // Smoke-test: register a single trivial component to prove the
        // CoreStartable lifecycle (register → start → injected component.run)
        // is wired up end-to-end. Removed once real components are stable.
        coreStartableComponent.register(DummyCoreStartable())

        coreStartableComponent.start()
        Log.i(TAG, "All CoreStartable services started; status bar visible, process is alive.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        coreStartableComponent.onConfigChanged(newConfig)
    }
}

/**
 * Minimal CoreStartable used as a smoke test: its start() only emits a log line.
 * Its presence proves that Hilt's injection of CoreStartableComponent and the
 // manual register→start path both work without pulling in any UI code.
 */
private class DummyCoreStartable : CoreStartable {
    override fun start() {
        Log.i("DummyStartable", "started OK")
    }
}
