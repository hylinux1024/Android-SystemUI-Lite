package com.android.systemui.lite.gesture.model

/**
 * Which region of the screen began a gesture. Pure classification of a touch-down coordinate —
 * no gesture state lives here, just the zone the finger landed in.
 *
 * Edge zones are intentionally ordered before BOTTOM so detectors can claim a side gesture
 * before the bottom band races it at the corners (matches AOSP gesture-nav behavior).
 */
enum class TouchZone {
    LEFT_EDGE,
    RIGHT_EDGE,
    BOTTOM,
    /** Top strip reserved for the notification-shade pull-down. Not produced by the legacy
     *  GestureZones detector (which predates the shade detector) — [ShadeGestureDetector] sets
     *  it directly when a touch lands in the status-bar band. */
    TOP,
    NONE,
}
