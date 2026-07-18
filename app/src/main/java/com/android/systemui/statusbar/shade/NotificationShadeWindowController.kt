package com.android.systemui.statusbar.shade

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.android.systemui.R
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and manages the TYPE_NOTIFICATION_SHADE window that hosts the expanded
 * notification panel. Mirrors AOSP `NotificationShadeWindowControllerImpl` in its
 * minimal form: window creation + a pull-based [apply] state machine.
 *
 * State changes (panel visible, scrim visible, blur radius) are coalesced into
 * [ShadeWindowState] and applied via [apply] — every setter ends up here.
 */
@Singleton
class NotificationShadeWindowController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager
) : CoreStartable {

    companion object {
        private const val TAG = "ShadeWindowCtrl"
    }

    /** Bitfield-style state object — the single source of truth for the window. */
    data class ShadeWindowState(
        var panelVisible: Boolean = false,
        var backgroundBlurRadius: Int = 0,
        var scrimsVisible: Boolean = false
    )

    private var shadeView: NotificationShadeWindowView? = null
    private var state = ShadeWindowState()

    override fun start() {
        createShadeWindow()
    }

    override fun stop() {
        shadeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove shade window", e)
            }
        }
        shadeView = null
    }

    // ------------------------------------------------------------------ window

    private fun createShadeWindow() {
        if (shadeView != null) return
        val windowView = NotificationShadeWindowView(context)
        shadeView = windowView

        // Inflate the panel layout into the shade window so that
        // NotificationPanelViewController can find R.id.notification_panel.
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.notification_panel, windowView, true)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            title = "NotificationShade"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager.addView(windowView, lp)
            Log.i(TAG, "notification shade window added (panel inflated)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add shade window", e)
        }
    }

    // ------------------------------------------------------------------ state

    fun getView(): NotificationShadeWindowView? = shadeView

    fun setPanelVisible(visible: Boolean) {
        if (state.panelVisible == visible) return
        state = state.copy(panelVisible = visible)
        apply()
    }

    fun setBackgroundBlurRadius(radius: Int) {
        if (state.backgroundBlurRadius == radius) return
        state = state.copy(backgroundBlurRadius = radius)
        apply()
    }

    fun setScrimsVisible(visible: Boolean) {
        if (state.scrimsVisible == visible) return
        state = state.copy(scrimsVisible = visible)
        apply()
    }

    fun isPanelVisible(): Boolean = state.panelVisible

    /**
     * Pull-based state machine — every setter ends up here. Updates the view
     * visibility and pushes the latest LayoutParams to WindowManager.
     */
    private fun apply() {
        val visible = isExpanded()
        shadeView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        // The panel view itself starts as gone in XML (so the empty shade window
        // does not block touches until the panel is first expanded). Propagate
        // window visibility to the panel so it can render.
        shadeView?.findViewById<View>(R.id.notification_panel)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    private fun isExpanded(): Boolean {
        val s = state
        return s.panelVisible || s.backgroundBlurRadius > 0 || s.scrimsVisible
    }
}
