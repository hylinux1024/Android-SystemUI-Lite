package com.android.systemui.navigationbar

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler
import com.android.systemui.navigationbar.gestural.EdgeBackPanelView
import com.android.systemui.wallpapers.WallpaperProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue component that owns the edge-swipe-back feature end-to-end and exposes it
 * as a [CoreStartable] so it slots into SystemUI-Lite's register → start
 * lifecycle.
 *
 * Responsibilities:
 *   - add a NOT_TOUCHABLE overlay window that hosts the visual arrow panel
 *     (mirrors AOSP `NavigationBarEdgePanel`'s window, which purely decorates);
 *   - register the [EdgeBackGestureHandler]'s InputMonitor so touch is observed
 *     before the focused window;
 *   - feed live wallpaper luminance to the panel for dark/light adaptation;
 *   - gate the gesture on [ForegroundAppTracker.isAppInForeground] so the
 *     side-swipe only fires when there is an opened app (per spec).
 *
 * The gesture's "trigger back" action is a real BACK key event injected through
 * [InputManager.injectInputEvent].
 */
@Singleton
class EdgeBackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager,
    private val foregroundAppTracker: ForegroundAppTracker,
    private val wallpaperProvider: WallpaperProvider,
    private val handler: EdgeBackGestureHandler
) : CoreStartable {

    companion object {
        private const val TAG = "EdgeBackController"

        // Edge geometry (dp). Mirrors AOSP navigation_edge_* dimens.
        private const val EDGE_WIDTH_DP = 16f
        private const val BOTTOM_GESTURE_HEIGHT_DP = 24f
        private const val EDGE_PANEL_WIDTH_DP = 48f
    }

    private var panelView: EdgeBackPanelView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var leftEdgeWidth = 0
    private var rightEdgeWidth = 0
    private var bottomGestureHeight = 0

    private val wallpaperListener = WallpaperProvider.OnWallpaperColorsChangedListener { data ->
        panelView?.setDarkMode(!data.isDark)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        Log.i(TAG, "start")
        computeEdgeDimensions()
        createPanelWindow()
        wireHandler()
        handler.setEnabled(true)
        wallpaperProvider.addListener(wallpaperListener)
        panelView?.setDarkMode(!wallpaperProvider.current.isDark)
    }

    override fun stop() {
        Log.i(TAG, "stop")
        handler.setEnabled(false)
        wallpaperProvider.removeListener(wallpaperListener)
        removePanelWindow()
    }

    override fun onConfigChanged(newConfig: Configuration) {
        computeEdgeDimensions()
        windowManager.updateViewLayout(panelView, layoutParams)
    }

    // ------------------------------------------------------------------- wiring

    private fun wireHandler() {
        // "only when an app is open": arm the gesture only in that case.
        handler.isAppInForeground = { foregroundAppTracker.isAppInForeground() }
        handler.leftEdgeWidthPx = leftEdgeWidth
        handler.rightEdgeWidthPx = rightEdgeWidth
        handler.bottomGestureHeightPx = bottomGestureHeight

        handler.onPanelMotionListener = { ev -> routeToPanel(ev) }
        handler.onBackTriggered = {
            Log.d(TAG, "injecting BACK key event")
            handler.injectBackEvent()
        }
        handler.onBackCancelled = {
            Log.d(TAG, "gesture cancelled")
        }
    }

    private fun isLeftEdge(event: MotionEvent): Boolean = event.x <= leftEdgeWidth

    /**
     * Normalise horizontal drag into a [0,1] progress for the chevron extension.
     */
    private fun computeProgress(event: MotionEvent): Float {
        val total = displayWidth()
        val dragged = Math.abs(event.x - startX).coerceAtMost(total.toFloat())
        return (dragged / total).coerceIn(0f, 1f)
    }

    // The panel needs the gesture start X; stash it from ACTION_DOWN.
    private var startX = 0f

    private fun routeToPanel(event: MotionEvent) {
        val panel = panelView ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                panel.resetForGesture(isLeftEdge(event), event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                panel.updateGesture(computeProgress(event), event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panel.hideArrow()
            }
        }
    }

    // --------------------------------------------------------------- windowing

    /**
     * Add the NOT_TOUCHABLE overlay that hosts the arrow panel.
     *
     * Full-screen (MATCH_PARENT × MATCH_PARENT): the overlay never intercepts
     * touch — it is purely decorative. [EdgeBackPanelView.onDraw] draws the
     * chevron at the correct physical edge from the full view width, so the
     * arrow appears on the same edge the finger started from. Because the
     * window is NOT_TOUCHABLE, normal app gestures and the notification shade
     * continue to work on top of it (touches fall through to windows below).
     */
    private fun createPanelWindow() {
        if (panelView != null) return
        val view = EdgeBackPanelView(context)
        panelView = view

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "EdgeBackPanel"
            setFitInsetsTypes(0)
            // Trusted overlay: this is a platform-signed system component.
            privateFlags = privateFlags or WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
        }
        layoutParams = lp

        try {
            windowManager.addView(view, lp)
            Log.i(TAG, "edge back panel window added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge back panel window", e)
        }
    }

    private fun removePanelWindow() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove edge back panel window", e)
            }
        }
        panelView = null
    }

    // --------------------------------------------------------------- geometry

    private fun computeEdgeDimensions() {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        // Default bar-gesture height ~ 16dp; detect inset from the resource when present.
        leftEdgeWidth = (EDGE_WIDTH_DP * density).toInt()
        rightEdgeWidth = (EDGE_WIDTH_DP * density).toInt()
        bottomGestureHeight = (BOTTOM_GESTURE_HEIGHT_DP * density).toInt()
        handler.leftEdgeWidthPx = leftEdgeWidth
        handler.rightEdgeWidthPx = rightEdgeWidth
        handler.bottomGestureHeightPx = bottomGestureHeight
        Log.d(TAG, "edge dims: L=$leftEdgeWidth R=$rightEdgeWidth bottom=$bottomGestureHeight")
    }

    private fun edgePanelWidth(): Int =
        (EDGE_PANEL_WIDTH_DP * context.resources.displayMetrics.density).toInt()

    private fun displayWidth(): Int {
        val metrics: WindowMetrics = windowManager.maximumWindowMetrics
        return metrics.bounds.width()
    }
}
