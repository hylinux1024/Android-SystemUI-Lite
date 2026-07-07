package com.android.systemui.statusbar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives auto-hide for transient bars (status bar / nav bar).
 *
 * Faithful port of SystemUI-Lite `AutoHideController`, which mirrors AOSP
 * `AutoHideController`. For the Phase 3 status-bar milestone only the
 * status-bar half is wired; the nav bar slot is reserved for a later phase.
 */
@Singleton
class AutoHideController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AutoHideController"
        const val AUTO_HIDE_TIMEOUT_MS = 2250
        const val USER_AUTO_HIDE_TIMEOUT_MS = 350
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusBar: AutoHideUiElement? = null
    private var navBar: AutoHideUiElement? = null
    private var isTransientBarAutoHiding = false
    private var isAutoHideSuspended = false

    private val autoHideRunnable = Runnable { hideTransientBars() }

    fun setStatusBar(element: AutoHideUiElement) { statusBar = element }
    fun setNavigationBar(element: AutoHideUiElement) { navBar = element }

    /** Triggered when the user touches the screen; decides whether to hide the bar. */
    fun checkUserAutoHide(event: MotionEvent) {
        val element = statusBar ?: return
        // Hidebars only when touch lands on the status bar region and the element opts in.
        if (element.isVisible() && element.shouldHideOnTouch() &&
            event.rawY <= ViewConfiguration.get(context).scaledTouchSlop && event.action == MotionEvent.ACTION_OUTSIDE) {
            userAutoHide()
        }
    }

    /** Touch landed on the bar itself (or gesture started); suspend auto-hide. */
    fun touchAutoHide() {
        isAutoHideSuspended = true
        cancelAutoHide()
    }

    fun scheduleAutoHide() {
        if (isAutoHideSuspended) return
        cancelAutoHide()
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_TIMEOUT_MS.toLong())
    }

    fun cancelAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        isTransientBarAutoHiding = false
    }

    private fun userAutoHide() {
        isTransientBarAutoHiding = true
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, USER_AUTO_HIDE_TIMEOUT_MS.toLong())
    }

    private fun suspendAutoHide() {
        isAutoHideSuspended = true
        cancelAutoHide()
    }

    fun resumeSuspendedAutoHide() {
        isAutoHideSuspended = false
        scheduleAutoHide()
    }

    private fun hideTransientBars() {
        statusBar?.hide()
        navBar?.hide()
        isTransientBarAutoHiding = false
    }
}
