package com.android.systemui.lite.gesture.zone

import com.android.systemui.lite.gesture.model.TouchZone
import kotlin.math.abs

/**
 * Pure, UI-free helpers that classify touch coordinates into gesture zones and decide
 * whether a touch should be ignored. No Compose, no Context, no pixels database — every
 * function here is a deterministic mapping of its inputs, so it can be unit-tested on
 * the JVM without an Android runtime.
 *
 * Edge zones are checked before the bottom zone everywhere so the back gesture always
 * takes priority over the home/recents swipe (matches AOSP gesture nav behavior). The
 * TOP zone (status-bar pull-down) is only produced when the caller explicitly asks for
 * it via [detectZone] with `includeTop = true` — legacy callers that pass `false` get
 * the same result as before, so the behavior change is opt-in.
 *
 * This is the canonical home for zone classification. The legacy copy in
 * `com.android.systemui.lite.navigation.GestureZones` is kept for one migration phase
 * while [com.android.systemui.lite.navigation.GestureHandler] still depends on it.
 */
object GestureZones {

    /** Bottom band of the screen, expressed as a fraction of display height. Touches at
     *  the very bottom belong to the home/recents swipe zone rather than content.
     *  Must be >= the actual bottom strip height (BOTTOM_STRIP_HEIGHT_DP dp) so every touch
     *  inside the strip is classified as BOTTOM — otherwise the strip's top portion is dead. */
    private const val BOTTOM_ZONE_FRACTION = 0.08f

    /** Top band of the screen, expressed as a fraction of display height, reserved for the
     *  notification-shade pull-down. Mirrors the status-bar height (~24dp on most devices). */
    private const val TOP_ZONE_FRACTION = 0.022f

    /** Right-edge strip used by [isExcluded] to recognise the rotation quick-switch area. */
    private const val EXCLUSION_EDGE_WIDTH_PX = 48f

    /** Fraction of screen height (measured from the bottom) that constitutes the rotation
     *  quick-switch exclusion zone at the left/right edges. */
    private const val EXCLUSION_BOTTOM_FRACTION = 0.20f

    /** Default touch slop in pixels — magnitude a drag must exceed before it counts as a
     *  deliberate horizontal back-swipe. */
    private const val DEFAULT_TOUCH_SLOP_PX = 48f

    /** Map a touch-down coordinate to a [TouchZone]. Edge zones are tested first so a drag
     *  beginning near a side (back) wins over the home/recents bottom zone even at the
     *  corners. Returns [TouchZone.NONE] for touches in screen interior.
     *
     *  @param includeTop when true, a touch in the top band returns [TouchZone.TOP] instead
     *      of [TouchZone.NONE]. Pass false for callers that don't host the shade detector.
     */
    fun detectZone(
        x: Float,
        y: Float,
        displayWidth: Int,
        displayHeight: Int,
        edgeWidthPx: Float,
        includeTop: Boolean = false,
    ): TouchZone {
        val bottomZoneHeightPx = displayHeight * BOTTOM_ZONE_FRACTION

        if (x < edgeWidthPx) return TouchZone.LEFT_EDGE
        if (x > displayWidth - edgeWidthPx) return TouchZone.RIGHT_EDGE
        if (y > displayHeight - bottomZoneHeightPx) return TouchZone.BOTTOM
        if (includeTop && y < displayHeight * TOP_ZONE_FRACTION) return TouchZone.TOP
        return TouchZone.NONE
    }

    /** True when the point sits in the bottom ~20% of a left/right edge. AOSP reserves this
     *  corner for the rotation quick-switch gesture, so the back-gesture handler must not
     *  claim drags that start here. */
    fun isExcluded(
        x: Float,
        y: Float,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        val nearEdge = x < EXCLUSION_EDGE_WIDTH_PX || x > displayWidth - EXCLUSION_EDGE_WIDTH_PX
        val nearBottom = y > displayHeight * (1f - EXCLUSION_BOTTOM_FRACTION)
        return nearEdge && nearBottom
    }

    /** True when a drag vector is primarily horizontal and has moved past touch slop — the
     *  signature of a back swipe. Vertical drags that happen to start at an edge (e.g.
     *  pulling down the notification shade) are rejected so they are not misread as back. */
    fun isEdgeGesture(dx: Float, dy: Float): Boolean {
        return abs(dx) > abs(dy) && abs(dx) > DEFAULT_TOUCH_SLOP_PX
    }
}
