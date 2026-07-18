package com.android.systemui.statusbar.shade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.statusbar.fling.FlingAnimations
import com.android.systemui.wallpapers.WallpaperProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Core panel controller. Owns the [TouchHandler] that captures vertical drags,
 * drives the real-time expanded height, and runs the expand/collapse
 * animations. Mirrors the heart of AOSP `NotificationPanelViewController`
 * (TouchHandler + fling + collapse/expand) in a minimal, dependency-free form.
 *
 * The panel slides out from the top edge via translationY on the panel view.
 * Expansion state is published through [ShadeExpansionStateManager] so that
 * [StatusBarTransitions] and future consumers (scrim, QS) can react.
 */
@Singleton
class NotificationPanelViewController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shadeWindowController: NotificationShadeWindowController,
    private val expansionStateManager: ShadeExpansionStateManager,
    private val wallpaperProvider: WallpaperProvider
) : CoreStartable {

    companion object {
        private const val TAG = "NotificationPanelVC"
        private const val EXPAND_DURATION_MS = 350L
        private const val COLLAPSE_DURATION_MS = 300L
        private const val EXPAND_OVERSHOOT_FRAC = 0.15f
    }

    private var panelView: NotificationPanelView? = null
    private val touchHandler = TouchHandler()

    // ---- expansion state (px) ----
    private var expandedHeight = 0f
    private var maxExpandedHeight = 0f
    private var heightAnimator: ValueAnimator? = null

    // ---- interpolators ----
    private val expandInterpolator = PathInterpolator(0.33f, 0f, 0f, 1f)
    private val collapseInterpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)

    val expansionFraction: Float
        get() = if (maxExpandedHeight > 0f) (expandedHeight / maxExpandedHeight).coerceIn(0f, 1f) else 0f

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        panelView = shadeWindowController.getView()
            ?.findViewById<NotificationPanelView>(R.id.notification_panel)
        shadeWindowController.getView()?.touchHandler = { ev -> touchHandler.onTouch(panelView, ev) }

        // Max expanded height = full display height (panel covers the whole screen
        // when fully open). Use the display height as a reliable fallback so the
        // panel is usable even before the shade window completes its layout pass.
        val displayHeight = context.resources.displayMetrics.heightPixels.toFloat()
        maxExpandedHeight = displayHeight
        Log.i(TAG, "maxExpandedHeight=${maxExpandedHeight}px (display)")

        // Refine once the view is actually laid out.
        panelView?.post {
            val laidOut = panelView?.height?.toFloat() ?: 0f
            if (laidOut > 0f) {
                maxExpandedHeight = laidOut
                Log.i(TAG, "maxExpandedHeight=${maxExpandedHeight}px (laid out)")
            }
        }

        // Panel background adapts to wallpaper luminance (matching SystemUI's
        // scrim behavior — dark tint on light wallpapers, light tint on dark).
        wallpaperProvider.addListener { data ->
            panelView?.applyBackgroundForWallpaper(data.isDark)
        }
        panelView?.applyBackgroundForWallpaper(wallpaperProvider.current.isDark)
    }

    override fun stop() {
        heightAnimator?.cancel()
        heightAnimator = null
    }

    // ---------------------------------------------------------------- public API

    // ---------------------------------------------------------------- public touch wrappers

    /**
     * Ask the panel's TouchHandler whether it wants to intercept [event].
     * Called by StatusBarManager on ACTION_DOWN. Returns true if the panel
     * should take over the gesture stream.
     */
    fun onInterceptTouch(event: MotionEvent): Boolean {
        return touchHandler.onInterceptTouchEvent(event)
    }

    /** Forward a MotionEvent to the panel's TouchHandler. */
    fun handleTouch(event: MotionEvent): Boolean {
        return touchHandler.onTouch(panelView as? View, event)
    }

    fun isFullyCollapsed(): Boolean = expansionFraction <= 0.001f

    fun isFullyExpanded(): Boolean = expansionFraction >= 0.999f

    fun collapse(animate: Boolean) {
        if (!animate) {
            setExpandedHeightInternal(0f)
            onPanelCollapsed()
            return
        }
        fling(expand = false)
    }

    fun expand(animate: Boolean) {
        if (!animate) {
            setExpandedHeightInternal(maxExpandedHeight)
            onPanelFullyExpanded()
            return
        }
        fling(expand = true)
    }

    /** Consume a back-press: collapse if expanded, return false if already collapsed. */
    fun onBackPressed(): Boolean {
        return if (!isFullyCollapsed()) {
            collapse(animate = true)
            true
        } else {
            false
        }
    }

    // ---------------------------------------------------------------- animation

    fun fling(expand: Boolean, velocity: Float = 0f) {
        val target = if (expand) maxExpandedHeight else 0f
        flingToHeight(velocity, expand, target, collapseSpeedUpFactor = 1f)
    }

    /**
     * Fling the panel to [target] using [velocity] to drive the animation.
     * Mirrors AOSP `flingToHeight(vel, expand, target, collapseSpeedUpFactor, ...)`.
     *
     * - Expanding + downward fling → overshoot past target, then springBack.
     * - Collapsing → faster velocity → shorter duration.
     * - Zero velocity → fixed-duration interpolator.
     */
    fun flingToHeight(velocity: Float, expand: Boolean, target: Float,
                      collapseSpeedUpFactor: Float = 1f, forcedDuration: Long? = null) {
        val start = expandedHeight
        val distance = abs(target - start)

        // Overshoot: only when expanding with a downward fling.
        val overshootPx = if (expand && velocity > 0f && distance > 0f) {
            FlingAnimations.computeOvershoot(velocity, getMaxOvershootPx())
        } else 0f

        val animTarget = target + overshootPx
        val anim = createHeightAnimator(animTarget)

        // forcedDuration overrides the computed duration (used for the dedicated
        // short snap-back when fraction is below the expand threshold).
        if (forcedDuration != null) {
            anim.duration = forcedDuration
            anim.interpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)
        } else if (expand) {
            // Expand animation — accelerate out.
            anim.interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
            anim.duration = if (velocity == 0f) {
                // No velocity → duration proportional to distance.
                FlingAnimations.computeSnapDuration(
                    distance, maxExpandedHeight, min = 150L, max = EXPAND_DURATION_MS)
            } else {
                FlingAnimations.computeFlingDuration(distance, velocity)
            }
        } else {
            // Collapse animation.
            if (velocity == 0f) {
                anim.interpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)
                // Snap-back: duration proportional to distance (short = snappy).
                anim.duration = FlingAnimations.computeSnapDuration(
                    distance, maxExpandedHeight, min = 100L, max = 350L)
            } else {
                anim.interpolator = PathInterpolator(0.33f, 0f, 1f, 1f)
                anim.duration = FlingAnimations.computeFlingDuration(distance, velocity)
            }
        }

        if (collapseSpeedUpFactor != 1f && velocity == 0f) {
            anim.duration = (anim.duration / collapseSpeedUpFactor.toLong()).coerceAtLeast(100L)
        }

        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (overshootPx > 0f && !cancelled) {
                    // Overshot past target → spring back.
                    springBack(target)
                } else {
                    setExpandedHeightInternal(target)  // Snap to exact target
                    if (!expand) onPanelCollapsed() else onPanelFullyExpanded()
                }
            }
        })
        heightAnimator?.cancel()
        heightAnimator = anim
        anim.start()

        Log.d(TAG, "flingToHeight expand=$expand vel=$velocity dist=${distance.toInt()} " +
            "overshoot=${overshootPx.toInt()} dur=${anim.duration}ms")
    }

    /**
     * Spring back from an overshot position to [target].
     * Mirrors AOSP `springBack()` — damped deceleration (FAST_OUT_SLOW_IN).
     */
    private fun springBack(target: Float) {
        val overshootDistance = expandedHeight - target
        if (overshootDistance <= 0.5f) {
            onPanelFullyExpanded()
            return
        }
        val anim = ValueAnimator.ofFloat(expandedHeight, target).apply {
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)  // FAST_OUT_SLOW_IN damping
            duration = 350L
            addUpdateListener {
                setExpandedHeightInternal(it.animatedValue as Float)
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onPanelFullyExpanded()
            }
        })
        heightAnimator?.cancel()
        heightAnimator = anim
        anim.start()
        Log.d(TAG, "springBack from=${expandedHeight.toInt()} to=${target.toInt()}")
    }

    private fun getMaxOvershootPx(): Float = maxExpandedHeight * 0.15f

    private fun createHeightAnimator(target: Float): ValueAnimator {
        return ValueAnimator.ofFloat(expandedHeight, target).apply {
            addUpdateListener {
                setExpandedHeightInternal(it.animatedValue as Float)
            }
        }
    }

    /** Set the expanded height and immediately update the view + publish state. */
    fun setExpandedHeightInternal(height: Float) {
        expandedHeight = height.coerceIn(0f, maxExpandedHeight)
        updateExpandedHeight()
    }

    private fun updateExpandedHeight() {
        // Slide the panel out from the top edge via translationY.
        panelView?.translationY = -(maxExpandedHeight - expandedHeight)
        panelView?.panelAlpha = 0.3f + 0.7f * expansionFraction
        Log.d(TAG, "updateExpandedHeight h=${expandedHeight}/${maxExpandedHeight} " +
            "frac=${expansionFraction} ty=${panelView?.translationY}")
        // Publish to listeners (StatusBarTransitions etc.).
        expansionStateManager.setExpansion(expansionFraction, tracking = false)
    }

    private fun onPanelCollapsed() {
        shadeWindowController.setPanelVisible(false)
        expansionStateManager.setExpansion(0f, tracking = false)
    }

    private fun onPanelFullyExpanded() {
        shadeWindowController.setPanelVisible(true)
        expansionStateManager.setExpansion(1f, tracking = false)
    }

    // ---------------------------------------------------------------- TouchHandler

    /**
     * Inner touch handler — the heart of gesture processing. Mirrors AOSP
     * `NPVC.TouchHandler`: captures vertical drags, tracks real-time expanded
     * height, and decides expand/collapse on release via fling velocity or
     * the half-way rule.
     *
     * Priority chain (simplified from AOSP): vertical drag > touchSlop →
     * capture; otherwise let the event pass through.
     */
    internal inner class TouchHandler {

        private var downX = 0f
        private var downY = 0f
        private var startY = 0f
        private var startExpandedHeight = 0f
        private var tracking = false
        private var velocityTracker: VelocityTracker? = null

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    initVelocityTracker()
                    velocityTracker?.addMovement(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val h = ev.y - downY
                    val w = ev.x - downX
                    // Capture BOTH downward (expand) and upward (collapse) drags,
                    // as long as they are mostly vertical and beyond touchSlop.
                    if (abs(h) > touchSlop && abs(h) > abs(w)) {
                        return true
                    }
                }
            }
            return false
        }

        fun onTouch(@Suppress("UNUSED_PARAMETER") v: View?, ev: MotionEvent): Boolean {
            Log.d(TAG, "TouchHandler.onTouch action=${motionEventActionName(ev.actionMasked)} " +
                "y=${ev.y} tracking=$tracking velTracker=${velocityTracker != null}")
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.y
                    startExpandedHeight = expandedHeight
                    // Initialise the velocity tracker here — this is the ONLY
                    // touch path that fires for status-bar-originated gestures,
                    // so the tracker must be created on DOWN, not in onIntercept.
                    initVelocityTracker()
                    velocityTracker?.addMovement(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)  // Feed every move so velocity is accurate
                    // dy > 0 → finger moved downward  → expand the panel.
                    // dy < 0 → finger moved upward    → collapse the panel.
                    val dy = ev.y - startY
                    if (abs(dy) > touchSlop && !tracking) {
                        tracking = true
                        shadeWindowController.setPanelVisible(true)
                        expansionStateManager.setExpansion(expansionFraction, tracking = true)
                    }
                    if (tracking) {
                        // New height = height-when-tracking-started + drag distance.
                        // Clamp to [0, maxExpandedHeight].
                        setExpandedHeightInternal(
                            (startExpandedHeight + dy).coerceIn(0f, maxExpandedHeight)
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking) {
                        tracking = false
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = velocityTracker?.yVelocity ?: 0f
                        val vx = velocityTracker?.xVelocity ?: 0f
                        endMotionEvent(vy, vx, ev.x, ev.y)
                    }
                    recycleVelocityTracker()
                    return true
                }
            }
            return false
        }

        /**
         * Decide expand vs collapse and drive the fling animation using the
         * release velocity. Mirrors AOSP `flingExpands()` + `endMotionEvent()`.
         */
        private fun endMotionEvent(velocityY: Float, velocityX: Float, x: Float, y: Float) {
            val vectorVel = hypot(velocityX, velocityY)
            // Distinguish real fling (direction decides) from slow drag (position decides).
            val expand = FlingAnimations.shouldExpandOnRelease(velocityY, vectorVel, expansionFraction)
            Log.d(TAG, "endMotionEvent vy=$velocityY vector=$vectorVel frac=$expansionFraction expand=$expand")

            // Snap-back (fraction below threshold, collapsing) gets a dedicated
            // short duration — distance-proportional, capped much lower so the
            // "barely dragged open, snaps shut" motion feels snappy.
            if (!expand && velocityY == 0f) {
                val distance = expandedHeight  // how far we need to travel back
                heightAnimator?.cancel()
                val duration = FlingAnimations.computeSnapDuration(
                    distance, maxExpandedHeight, min = 80L, max = 180L)
                flingToHeight(velocity = 0f, expand = false, target = 0f,
                    forcedDuration = duration)
                return
            }

            // Pass velocity to drive animation duration + overshoot.
            flingToHeight(velocity = velocityY, expand = expand,
                target = if (expand) maxExpandedHeight else 0f)
        }

        private fun initVelocityTracker() {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun motionEventActionName(action: Int): String = when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "ACTION_$action"
        }
    }
}
