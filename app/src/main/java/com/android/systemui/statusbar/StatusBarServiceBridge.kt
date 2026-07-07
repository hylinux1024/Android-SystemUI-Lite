package com.android.systemui.statusbar

import android.util.Log
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between SystemUI and the framework StatusBarManagerService via IStatusBar.
 *
 * In AOSP this is `CommandQueue extends IStatusBar.Stub`, driven through
 * `IStatusBarService.registerStatusBar()` handshake with the window manager.
 * In our standalone build (no system_server owning the same binder state) that
 * handshake would deadlock the boot path, so this bridge exposes the same
 * `BarCallback` interface and slots but does NOT register against
 * `StatusBarManagerService` in Phase 3. It is wired so a future system-server
 * integration can call `registerStatusBar()` behind a single feature flag.
 *
 * Mirrors AOSP `CentralSurfacesCommandQueueCallbacks`:
 *  - `onBarVisibilityChanged` → set the attached status bar view visibility.
 *  - `onTransientStateChanged` → alpha shift (transient mode).
 *  - `onAppearanceChanged` → consumed by nav bar / lock screen in AOSP; no-op here.
 */
@Singleton
class StatusBarServiceBridge @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) : CoreStartable {

    companion object {
        private const val TAG = "StatusBarServiceBridge"
    }

    interface BarCallback {
        fun onBarVisibilityChanged(visible: Boolean)
        fun onTransientStateChanged(isTransient: Boolean)
        fun onAppearanceChanged(appearance: Int)
    }

    var statusBarCallback: BarCallback? = null
    var navBarCallback: BarCallback? = null

    private var isRegistered = false

    override fun start() {
        // Local UI mode: do not bind to StatusBarManagerService in the standalone build.
        Log.i(TAG, "StatusBarServiceBridge started (local UI mode)")
        isRegistered = true
    }

    override fun stop() {
        isRegistered = false
    }
}
