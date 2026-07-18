package com.android.systemui.statusbar.fling

import android.util.MathUtils
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure functions that map fling velocity → animation parameters.
 *
 * Mirrors AOSP `FlingAnimationUtils` (WMShell) in a self-contained form so the
 * panel's fling physics doesn't depend on the WMShell module:
 *  - duration scales inversely with velocity (faster fling → shorter animation)
 *  - overshoot amount scales with velocity (faster fling → more overshoot)
 *  - everything is clamped to sensible bounds.
 */
object FlingAnimations {

    /** AOSP FlingAnimationUtils experience values (units: ms / px / px-per-s). */
    private const val MIN_FLING_DURATION_MS = 100L
    private const val MAX_FLING_DURATION_MS = 320L
    private const val MIN_VELOCITY_PX_PER_SEC = 200f
    private const val HIGH_VELOCITY_PX_PER_SEC = 4000f

    /**
     * Duration for a no-velocity animation (snap-back / slow-drag release),
     * derived from the distance to travel. Shorter distance → proportionally
     * shorter duration, clamped to [min, max].
     *
     * @param distancePx     pixels to travel
     * @param fullHeightPx   panel's full expanded height (used for ratio)
     * @param min            lower bound ms
     * @param max            upper bound ms
     */
    fun computeSnapDuration(distancePx: Float, fullHeightPx: Float, min: Long, max: Long): Long {
        val ratio = if (fullHeightPx > 0f) (distancePx / fullHeightPx).coerceIn(0f, 1f) else 0f
        // Linear: min at zero distance → max at full distance.
        return (min + ((max - min) * ratio).toLong()).coerceIn(min, max)
    }

    /**
     * Animation duration — faster fling → shorter duration.
     *
     * @param distancePx         pixel distance from start to target
     * @param velocityPxPerSec   release speed in px/s (absolute value used)
     * @return duration in ms, clamped to [MIN_FLING_DURATION_MS, MAX_FLING_DURATION_MS]
     */
    fun computeFlingDuration(distancePx: Float, velocityPxPerSec: Float): Long {
        val speed = abs(velocityPxPerSec)
        if (speed <= 0f) return MAX_FLING_DURATION_MS
        // Empirical: duration ≈ distance / speed, clamped
        val computed = (distancePx / speed * 1000f).toLong()
        return computed.coerceIn(MIN_FLING_DURATION_MS, MAX_FLING_DURATION_MS)
    }

    /**
     * Whether a gesture is fast enough to count as a real fling (slow drags use
     * the fraction-based rule instead).
     *
     * @param vectorVelocity  speed magnitude sqrt(vx² + vy²)
     */
    fun isFling(vectorVelocity: Float): Boolean {
        return vectorVelocity >= MIN_VELOCITY_PX_PER_SEC
    }

    /**
     * Overshoot distance (px) — when flung downward, the panel overshoots the
     * target by this much before springing back.
     *
     * @param velocityY      vertical velocity (downward = positive)
     * @param maxOvershootPx maximum allowed overshoot (typically ~15% of panel height)
     * @return overshoot in px (0 if velocityY <= 0)
     */
    fun computeOvershoot(velocityY: Float, maxOvershootPx: Float): Float {
        if (velocityY <= 0f) return 0f
        val speedRatio = MathUtils.saturate(velocityY / HIGH_VELOCITY_PX_PER_SEC)
        val overshootFraction = MathUtils.lerp(0.2f, 1.0f, speedRatio)
        return overshootFraction * maxOvershootPx
    }

    /**
     * Decide whether a release should expand or collapse the panel.
     *
     * @param velocityY      vertical velocity (downward = positive)
     * @param vectorVelocity speed magnitude sqrt(vx² + vy²)
     * @param fraction       current expansion fraction [0, 1]
     * @return true = expand, false = collapse
     */
    fun shouldExpandOnRelease(velocityY: Float, vectorVelocity: Float, fraction: Float): Boolean {
        return if (isFling(vectorVelocity)) {
            // Real fling: direction decides (down = expand, up = collapse)
            velocityY > 0f
        } else {
            // Slow drag release: position decides (closer to which end wins)
            fraction > 0.3f
        }
    }
}
