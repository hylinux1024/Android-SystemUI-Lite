package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.NavigationMode
import com.android.systemui.lite.navigation.GestureHandler
import com.android.systemui.lite.ui.navigation.NavigationBarView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext

class NavigationBarCoreStartable(private val context: Context) : CoreStartable {

    companion object {
        private const val TAG = "NavigationBarCoreStartable"
        // 2019 = TYPE_NAVIGATION_BAR.
        @Suppress("DEPRECATION")
        private val TYPE_NAVIGATION_BAR = 2019
        private const val SETTINGS_NAVIGATION_MODE = "navigation_mode"
    }

    private val windowHost = WindowHost()
    private var navBarView: ComposeView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    /** Process-wide gesture state machine from Koin (US-007 AC3). Wired by [wireHandlerAction]. */
    private val gestureHandler: GestureHandler = GlobalContext.get().get()

    /**
     * Observed navigation mode. Held in a [StateFlow] so the Compose tree re-renders on a live
     * flip without the window being torn down (which would race the system's type-2019 slot).
     */
    private val _mode = MutableStateFlow(detectNavigationMode())
    private val mode: StateFlow<NavigationMode> = _mode.asStateFlow()

    /** Mutable layout params reused across [applyNavigationMode] via [WindowManager.updateViewLayout]. */
    private var layoutParams = newLayoutParams(NavigationMode.THREE_BUTTON)

    private var navModeObserver: ContentObserver? = null

    override fun start() {
        Log.d(TAG, "Starting NavigationBarCoreStartable...")
        windowHost.start()
        wireHandlerAction()
        initNavBarWindow()
        applyNavigationMode(mode.value, reshapeWindow = true)
        registerNavModeObserver()
        Log.d(TAG, "NavigationBarCoreStartable started")
    }

    /** US-007 AC3 — attach the real key-event dispatch to the Koin [GestureHandler] singleton. */
    private fun wireHandlerAction() {
        gestureHandler.onAction = { gesture ->
            Log.d(TAG, "gesture committed: $gesture")
            when (gesture) {
                GestureType.BACK -> sendKeyEvent(4)
                GestureType.HOME -> sendKeyEvent(3)
                GestureType.RECENTS -> sendKeyEvent(187)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // US-007 AC4 — refresh geometry, cancel any in-flight gesture, then honor a possible
        // nav-mode change that accompanied the configuration change (e.g. folding a foldable).
        val dm = context.resources.displayMetrics
        gestureHandler.updateDisplaySize(dm.widthPixels, dm.heightPixels)
        gestureHandler.resetSession()
        gestureHandler.refreshNavigationMode()
        applyNavigationMode(detectNavigationMode(), reshapeWindow = true)
    }

    override fun onBootCompleted() {}

    override fun stop() {
        unregisterNavModeObserver()
        navBarView?.let { view ->
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing nav bar window: ${e.message}")
            }
        }
        navBarView = null
        windowHost.destroy()
    }

    /** Create the single persistent ComposeView once; content switches reactively via [mode]. */
    private fun initNavBarWindow() {
        val dm = context.resources.displayMetrics
        navBarView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setContent {
                MaterialTheme {
                    val currentMode by mode.collectAsState()
                    NavigationBarView(
                        themeColor = androidx.compose.ui.graphics.Color(0xFF00ADB5),
                        navigationMode = currentMode,
                        handler = gestureHandler,
                        onBack = { sendKeyEvent(4) },
                        onHome = { sendKeyEvent(3) },
                        onRecents = { sendKeyEvent(187) }
                    )
                }
            }
        }
    }

    /**
     * Set the live [Mode] reshaping the persistent window in place via
     * [WindowManager.updateViewLayout] (US-007 AC1 + AC2). The Compose tree observes [mode] and
     * flips between the three-button strip and the gesture root without a window re-add.
     */
    private fun applyNavigationMode(mode: NavigationMode, reshapeWindow: Boolean) {
        val changed = mode != _mode.value
        _mode.value = mode
        if (!reshapeWindow) return

        layoutParams = newLayoutParams(mode)
        navBarView?.let { view ->
            try {
                wm.updateViewLayout(view, layoutParams)
                Log.d(TAG, "NavigationBar window reshaped (mode=$mode, flags=${layoutParams.flags})")
            } catch (e: IllegalArgumentException) {
                // view not attached yet (very first apply before addView) — add it.
                safeAddView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating nav bar window: ${e.message}")
            }
        }
    }

    /** US-007 AC1 + AC2: size and flag window spec per navigation mode. */
    private fun newLayoutParams(mode: NavigationMode): WindowManager.LayoutParams {
        val navBarHeight = getNavigationBarHeightPx()
        return WindowManager.LayoutParams().apply {
            @Suppress("DEPRECATION")
            type = TYPE_NAVIGATION_BAR
            format = PixelFormat.TRANSLUCENT
            setTitle("NavigationBar")
            packageName = context.packageName
            setFitInsetsTypes(0)
            when (mode) {
                NavigationMode.GESTURES -> {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    gravity = Gravity.TOP
                    // NOT_TOUCHABLE ABSENT so edge/bottom swipes reach Compose pointerInput;
                    // LAYOUT_IN_SCREEN so the window spans the display; NOT_FOCUSABLE KEPT so the
                    // IME is unaffected.
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }
                NavigationMode.THREE_BUTTON -> {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = navBarHeight
                    gravity = Gravity.BOTTOM
                    // Touchable bottom strip only; NOT_TOUCHABLE would also drop the buttons, so we
                    // keep the original flags. The strip occupies only navBarHeight, so app touches
                    // elsewhere fall through to windows below.
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                }
            }
        }
    }

    private fun safeAddView(view: ComposeView) {
        try {
            wm.addView(view, layoutParams)
            Log.d(TAG, "NavigationBar window added (mode=${mode.value}, flags=${layoutParams.flags})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add nav bar window: ${e.message}")
        }
    }

    private fun getNavigationBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val height = if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
        return if (height > 0) height else (48 * context.resources.displayMetrics.density).toInt()
    }

    private fun detectNavigationMode(): NavigationMode {
        return try {
            val mode = Settings.Secure.getInt(
                context.contentResolver, SETTINGS_NAVIGATION_MODE, 0
            )
            if (mode == 2) NavigationMode.GESTURES else NavigationMode.THREE_BUTTON
        } catch (e: Exception) {
            NavigationMode.THREE_BUTTON
        }
    }

    /** US-007 AC5 — watch Settings.Secure.NAVIGATION_MODE and reshape on flip. */
    private fun registerNavModeObserver() {
        if (navModeObserver != null) return
        val uri = Settings.Secure.getUriFor(SETTINGS_NAVIGATION_MODE) ?: return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post {
                    val detected = detectNavigationMode()
                    if (detected != _mode.value) {
                        Log.d(TAG, "NAVIGATION_MODE changed -> $detected; reshaping navbar")
                        gestureHandler.refreshNavigationMode()
                        applyNavigationMode(detected, reshapeWindow = true)
                    }
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(uri, false, observer)
            navModeObserver = observer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register nav-mode observer: ${e.message}")
        }
    }

    private fun unregisterNavModeObserver() {
        navModeObserver?.let { observer ->
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering nav-mode observer: ${e.message}")
            }
        }
        navModeObserver = null
    }

    private fun sendKeyEvent(keyCode: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key event $keyCode: ${e.message}")
        }
    }
}
