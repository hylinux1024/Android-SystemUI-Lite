package com.android.systemui.lite.systemui.statusbar

import android.content.Context
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * StatusBarWindowController manages the TYPE_STATUS_BAR system window.
 *
 * This mirrors AOSP's StatusBarWindowController which creates and manages
 * the status bar window with the correct LayoutParams, insets, and flags.
 *
 * The status bar window is a system window that:
 * - Sits at the top of the screen
 * - Provides statusBars() insets to apps below
 * - Is not focusable (touches pass through to apps)
 * - Is drawn by the SystemUI process
 */
class StatusBarWindowController(private val context: Context) {

    companion object {
        private const val TAG = "StatusBarWindowController"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // The status bar view and its layout parameters
    private var statusBarView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Token for this window (unique identifier)
    private val windowToken = Binder()

    /**
     * Attach the status bar window to WindowManager.
     *
     * @param view The view to display in the status bar window
     * @param heightPx The height of the status bar in pixels
     */
    fun attach(view: View, heightPx: Int) {
        if (statusBarView != null) {
            Log.w(TAG, "Status bar window already attached, removing first")
            detach()
        }

        statusBarView = view
        layoutParams = createLayoutParams(heightPx)

        try {
            windowManager.addView(view, layoutParams)
            Log.d(TAG, "Status bar window attached (TYPE_STATUS_BAR, height=${heightPx}px)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach status bar window: ${e.message}", e)
        }
    }

    /**
     * Detach the status bar window from WindowManager.
     */
    fun detach() {
        statusBarView?.let { view ->
            try {
                windowManager.removeView(view)
                Log.d(TAG, "Status bar window detached")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detach status bar window: ${e.message}", e)
            }
        }
        statusBarView = null
        layoutParams = null
    }

    /**
     * Update the status bar height.
     */
    fun updateHeight(heightPx: Int) {
        statusBarView?.let { view ->
            layoutParams?.let { params ->
                params.height = heightPx
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update status bar height: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Get the status bar height from system resources.
     */
    fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            // Default to 25dp if resource not found
            val density = context.resources.displayMetrics.density
            (25 * density).toInt()
        }
    }

    /**
     * Create the WindowManager.LayoutParams for the status bar window.
     *
     * This matches AOSP's StatusBarWindowController.getBarLayoutParamsForRotation():
     * - TYPE_STATUS_BAR: System window type for status bar
     * - FLAG_NOT_FOCUSABLE: Don't steal focus from apps
     * - FLAG_SPLIT_TOUCH: Allow split touch events
     * - FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS: We draw the system bar backgrounds
     * - Gravity.TOP: Position at top of screen
     * - LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS: Extend into display cutout
     */
    @Suppress("DEPRECATION")
    private fun createLayoutParams(heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Unique token for this window
            token = windowToken

            // Position at top
            gravity = Gravity.TOP

            // Don't let insets adjust our position
            setFitInsetsTypes(0)

            // Window title for debugging
            setTitle("StatusBar")

            // Package name
            packageName = context.packageName

            // Extend into display cutout
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

            // System UI visibility flags
            systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /**
     * Update the window LayoutParams.
     */
    fun updateLayoutParams(update: WindowManager.LayoutParams.() -> Unit) {
        statusBarView?.let { view ->
            layoutParams?.let { params ->
                params.update()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update layout params: ${e.message}", e)
                }
            }
        }
    }
}
