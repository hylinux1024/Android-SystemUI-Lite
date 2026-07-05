package com.android.systemui.lite.gesture.model

/**
 * Immutable snapshot of an in-flight gesture. Replaced wholesale on every state transition
 * (never mutated in place) so Compose sees a single `collectAsState()` value change and
 * affordance animations interpolate cleanly.
 *
 * @param zone       which screen region the finger started in.
 * @param startX     display-space x where the gesture began (px).
 * @param startY     display-space y where the gesture began (px).
 * @param startTimeMs wall-clock millis of the touch-down — used by long-press guards.
 * @param state      current lifecycle state.
 * @param progress   0..1 how far toward completion the drag has moved. Meaning is
 *                   zone-specific: for BACK it's drag distance / half-screen-width; for
 *                   SHADE it's already the shade-open fraction (so the scrim can drive its
 *                   alpha directly from this field).
 */
data class GestureSession(
    val zone: TouchZone,
    val startX: Float,
    val startY: Float,
    val startTimeMs: Long,
    val state: GestureState,
    val progress: Float,
)
