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
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.model.GestureEdge
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.NavigationMode
import com.android.systemui.lite.navigation.GestureHandler
import com.android.systemui.lite.ui.navigation.GestureBottomZone
import com.android.systemui.lite.ui.navigation.GestureEdgeZone
import com.android.systemui.lite.ui.navigation.NavigationBarView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext

class NavigationBarCoreStartable(private val context: Context) : CoreStartable {

    companion object {
        private const val TAG = "NavigationBarCoreStartable"
        // 2019 = TYPE_NAVIGATION_BAR — only ONE window of this type is allowed per process, so it
        // is used for the three-button bottom strip only.
        @Suppress("DEPRECATION")
        private val TYPE_NAVIGATION_BAR = 2019
        // 2024 = TYPE_NAVIGATION_BAR_PANEL — AOSP's type for gesture overlay windows. Multiple
        // windows of this type are allowed, so the three gesture strips each get their own.
        @Suppress("DEPRECATION")
        private val TYPE_NAVIGATION_BAR_PANEL = 2024
        private const val SETTINGS_NAVIGATION_MODE = "navigation_mode"

        // Strip geometry — thin enough that the center of the screen stays free for apps.
        private const val EDGE_STRIP_WIDTH_DP = 48
        private const val BOTTOM_STRIP_HEIGHT_DP = 60
        // Bottom ~20% of the left/right edge strips is excluded (rotation hotspot per US-006).
        private const val EDGE_BOTTOM_EXCLUDE_FRACTION = 0.20f
    }

    private val windowHost = WindowHost()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    /** Recents panel overlay — shows our own recents UI instead of the launcher's empty shell. */
    private val recentsCoreStartable: RecentsCoreStartable? by lazy {
        try {
            com.android.systemui.lite.SystemUIApplication.instance
                .getStartable(RecentsCoreStartable::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "RecentsCoreStartable not available: ${e.message}")
            null
        }
    }

    /** Process-wide gesture state machine from Koin (US-007 AC3). Wired by [wireHandlerAction]. */
    private val gestureHandler: GestureHandler = GlobalContext.get().get()

    /**
     * Observed navigation mode. Held in a [StateFlow] so the Compose tree re-renders on a live
     * flip without the window being torn down (which would race the system's type-2019 slot).
     */
    private val _mode = MutableStateFlow(detectNavigationMode())
    private val mode: StateFlow<NavigationMode> = _mode.asStateFlow()

    // THREE_BUTTON mode: single bottom ComposeView hosting the three-button bar.
    private var threeButtonView: ComposeView? = null

    // GESTURE mode: three thin strip ComposeViews — left edge, right edge, bottom. Each window
    // only covers its own band, so touches in the center of the screen fall through to apps.
    private var leftEdgeView: ComposeView? = null
    private var rightEdgeView: ComposeView? = null
    private var bottomZoneView: ComposeView? = null

    private var navModeObserver: ContentObserver? = null

    override fun start() {
        Log.d(TAG, "Starting NavigationBarCoreStartable...")
        windowHost.start()
        wireHandlerAction()
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
                GestureType.RECENTS -> launchRecents()
            }
        }
    }

    /**
     * Open the recents (recent apps) UI. We show our own [RecentsCoreStartable] overlay panel
     * instead of the launcher's RecentsActivity — the launcher activity opens as an empty shell
     * when launched directly because AOSP's recents card pipeline (OverviewProxyService →
     * IOverviewProxy → RecentsImplementation) only runs when the real SystemUI drives it.
     */
    private fun launchRecents() {
        val panel = recentsCoreStartable
        if (panel != null) {
            Log.d(TAG, "showing recents panel")
            panel.showRecents()
        } else {
            Log.w(TAG, "RecentsCoreStartable unavailable — falling back to KEYCODE_RECENTS")
            sendKeyEvent(187)
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
        removeViewSafely(threeButtonView)
        threeButtonView = null
        removeViewSafely(leftEdgeView)
        leftEdgeView = null
        removeViewSafely(rightEdgeView)
        rightEdgeView = null
        removeViewSafely(bottomZoneView)
        bottomZoneView = null
        windowHost.destroy()
    }

    private fun removeViewSafely(view: ComposeView?) {
        view?.let { v ->
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing window: ${e.message}")
            }
        }
    }

    /**
     * Switch between the three-button bottom strip and the three-strip gesture overlay. Each mode
     * tears down the other's windows so only one set is attached at a time.
     */
    private fun applyNavigationMode(newMode: NavigationMode, reshapeWindow: Boolean) {
        val changed = newMode != _mode.value
        _mode.value = newMode
        if (!reshapeWindow) return

        when (newMode) {
            NavigationMode.THREE_BUTTON -> {
                // Tear down gesture strip windows.
                removeViewSafely(leftEdgeView); leftEdgeView = null
                removeViewSafely(rightEdgeView); rightEdgeView = null
                removeViewSafely(bottomZoneView); bottomZoneView = null
                // Create (or reshape) the three-button bottom strip.
                if (threeButtonView == null) {
                    threeButtonView = createThreeButtonView()
                    safeAddView(threeButtonView!!, threeButtonParams())
                } else {
                    safeUpdateView(threeButtonView!!, threeButtonParams())
                }
            }
            NavigationMode.GESTURES -> {
                // Tear down the three-button strip.
                removeViewSafely(threeButtonView); threeButtonView = null
                // Create (or reshape) the three gesture strip windows.
                val dm = context.resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels
                if (leftEdgeView == null) {
                    leftEdgeView = createEdgeZoneView(GestureEdge.LEFT, screenW, screenH)
                    rightEdgeView = createEdgeZoneView(GestureEdge.RIGHT, screenW, screenH)
                    bottomZoneView = createBottomZoneView(screenW, screenH)
                    safeAddView(leftEdgeView!!, edgeParams(isLeft = true))
                    safeAddView(rightEdgeView!!, edgeParams(isLeft = false))
                    safeAddView(bottomZoneView!!, bottomZoneParams())
                } else {
                    safeUpdateView(leftEdgeView!!, edgeParams(isLeft = true))
                    safeUpdateView(rightEdgeView!!, edgeParams(isLeft = false))
                    safeUpdateView(bottomZoneView!!, bottomZoneParams())
                }
            }
        }
        Log.d(TAG, "NavigationBar windows reshaped (mode=$newMode)")
    }

    // --- View factories ---

    private fun createThreeButtonView(): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(windowHost)
        setViewTreeViewModelStoreOwner(windowHost)
        setViewTreeSavedStateRegistryOwner(windowHost)
        setContent {
            MaterialTheme {
                NavigationBarView(
                    themeColor = androidx.compose.ui.graphics.Color(0xFF00ADB5),
                    navigationMode = NavigationMode.THREE_BUTTON,
                    handler = gestureHandler,
                    onBack = { sendKeyEvent(4) },
                    onHome = { sendKeyEvent(3) },
                    onRecents = { launchRecents() }
                )
            }
        }
    }

    private fun createEdgeZoneView(edge: GestureEdge, screenW: Int, screenH: Int): ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)
            setContent {
                MaterialTheme {
                    GestureEdgeZone(gestureHandler, edge, screenW, screenH)
                }
            }
        }

    private fun createBottomZoneView(screenW: Int, screenH: Int): ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)
            setContent {
                MaterialTheme {
                    GestureBottomZone(gestureHandler, screenW, screenH)
                }
            }
        }

    // --- Layout params ---

    private fun threeButtonParams(): WindowManager.LayoutParams {
        val navBarHeight = getNavigationBarHeightPx()
        return WindowManager.LayoutParams().apply {
            @Suppress("DEPRECATION")
            type = TYPE_NAVIGATION_BAR
            format = PixelFormat.TRANSLUCENT
            setTitle("NavigationBar")
            packageName = context.packageName
            setFitInsetsTypes(0)
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

    private fun edgeParams(isLeft: Boolean): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val edgeWidthPx = (EDGE_STRIP_WIDTH_DP * dm.density).toInt()
        val screenH = dm.heightPixels
        // Exclude the bottom ~20% so the rotation hotspot stays in the app's domain.
        val stripHeight = (screenH * (1f - EDGE_BOTTOM_EXCLUDE_FRACTION)).toInt()
        return WindowManager.LayoutParams().apply {
            @Suppress("DEPRECATION")
            type = TYPE_NAVIGATION_BAR_PANEL
            format = PixelFormat.TRANSLUCENT
            setTitle(if (isLeft) "GestureEdgeLeft" else "GestureEdgeRight")
            packageName = context.packageName
            setFitInsetsTypes(0)
            width = edgeWidthPx
            height = stripHeight
            x = 0
            y = 0
            gravity = if (isLeft) Gravity.LEFT or Gravity.TOP else Gravity.RIGHT or Gravity.TOP
            // NOT_FOCUSABLE: don't steal IME. NOT_TOUCH_MODAL: touches outside this window's
            // bounds (i.e. everywhere except the 48dp strip) keep going to windows below.
            // FLAG_LAYOUT_IN_SCREEN: span the display. NO NOT_TOUCHABLE — the strip itself must
            // receive drags for the gesture detector.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
    }

    private fun bottomZoneParams(): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val stripHeightPx = (BOTTOM_STRIP_HEIGHT_DP * dm.density).toInt()
        return WindowManager.LayoutParams().apply {
            @Suppress("DEPRECATION")
            type = TYPE_NAVIGATION_BAR_PANEL
            format = PixelFormat.TRANSLUCENT
            setTitle("GestureBottom")
            packageName = context.packageName
            setFitInsetsTypes(0)
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = stripHeightPx
            gravity = Gravity.BOTTOM
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
    }

    private fun safeAddView(view: ComposeView, params: WindowManager.LayoutParams) {
        try {
            wm.addView(view, params)
            Log.d(TAG, "Window added (title=${params.title}, flags=${params.flags})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add window ${params.title}: ${e.message}")
        }
    }

    private fun safeUpdateView(view: ComposeView, params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // View not attached — add it.
            safeAddView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window ${params.title}: ${e.message}")
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
