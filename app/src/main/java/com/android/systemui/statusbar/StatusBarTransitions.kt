package com.android.systemui.statusbar

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import com.android.systemui.statusbar.shade.ShadeExpansionListener
import com.android.systemui.statusbar.shade.ShadeExpansionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Animates the alpha of status-bar icons based on the panel-expansion state
 * and on bar-mode transitions (transparent / semi-transparent / opaque / lights-out).
 *
 * Mirrors AOSP `PhoneStatusBarTransitions` (which extends `BarTransitions`).
 *
 * Layout contract — the views referenced here come from `status_bar.xml`:
 *  - [startSide]      = R.id.status_bar_start_side_except_heads_up
 *  - [statusIcons]    = R.id.statusIcons
 *  - [battery]        = R.id.battery
 */
@Singleton
class StatusBarTransitions(
    private val startSide: View,
    private val statusIcons: View,
    private val battery: View
) : ShadeExpansionListener {

    enum class BarMode(val iconAlpha: Float) {
        /** Fully transparent background — icons fully visible. */
        TRANSPARENT(1f),
        /** Semi-transparent background — icons fully visible. */
        SEMI_TRANSPARENT(1f),
        /** Translucent background — icons slightly dimmed. */
        TRANSLUCENT(0.8f),
        /** Lights-out — non-battery/clock icons hidden. */
        LIGHTS_OUT(0f),
        /** Opaque background — icons drawn at the opaque alpha. */
        OPAQUE(0.94f),
        /** Warning tint — icons fully visible. */
        WARNING(1f),
        /** Lights-out + transparent — icons hidden. */
        LIGHTS_OUT_TRANSPARENT(0f);

        /** Battery & clock stay partially visible during lights-out. */
        val batteryClockAlpha: Float
            get() = if (this == LIGHTS_OUT) 0.5f else iconAlpha
    }

    companion object {
        private const val LIGHTS_OUT_DURATION = 1500L
        private const val MODE_TRANSITION_DURATION = 250L
    }

    private var currentMode = BarMode.TRANSPARENT
    private var currentAnimation: AnimatorSet? = null

    /**
     * Transition to a new bar [mode]. Animates the icon alpha when [animate] is true,
     * otherwise applies it immediately.
     */
    fun transitionTo(mode: BarMode, animate: Boolean = true) {
        if (mode == currentMode) return
        val oldMode = currentMode
        currentMode = mode
        applyMode(mode, animate, oldMode)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyMode(mode: BarMode, animate: Boolean, oldMode: BarMode) {
        val iconAlpha = mode.iconAlpha
        val batteryAlpha = mode.batteryClockAlpha
        currentAnimation?.cancel()
        if (animate) {
            currentAnimation = AnimatorSet().apply {
                playTogether(
                    animateTransitionTo(startSide, iconAlpha),
                    animateTransitionTo(statusIcons, iconAlpha),
                    animateTransitionTo(battery, batteryAlpha)
                )
                duration = if (mode == BarMode.LIGHTS_OUT) LIGHTS_OUT_DURATION
                    else MODE_TRANSITION_DURATION
                start()
            }
        } else {
            startSide.alpha = iconAlpha
            statusIcons.alpha = iconAlpha
            battery.alpha = batteryAlpha
        }
    }

    fun animateTransitionTo(v: View, toAlpha: Float): ObjectAnimator {
        return ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, toAlpha)
    }

    // ---------------------------------------------- ShadeExpansionListener

    /**
     * React to panel-expansion changes: as the panel drags open, fade the status
     * bar icons out so the wallpaper/content shows through. Restores them when
     * the panel collapses.
     */
    override fun onPanelExpansionChanged(state: ShadeExpansionState) {
        when {
            state.fraction >= 0.9f -> {
                // Fully expanded → fade status bar out.
                startSide.alpha = 0f
                statusIcons.alpha = 0f
            }
            state.fraction <= 0.05f -> {
                // Fully collapsed → restore full visibility.
                startSide.alpha = 1f
                statusIcons.alpha = 1f
            }
            else -> {
                // Interpolate alpha between 1.0 (collapsed) → 0.0 (expanded).
                val alpha = (1f - state.fraction).coerceIn(0f, 1f)
                startSide.alpha = alpha
                statusIcons.alpha = alpha
            }
        }
    }
}
