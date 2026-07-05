package com.android.systemui.lite.gesture.model

/**
 * High-level lifecycle of a single gesture session. Every [GestureSession] in flight carries
 * one of these — Compose affordances read it to choose which geometry targets to animate to.
 *
 *  - GONE     — no in-flight session.
 *  - TRACKING — finger-down in a valid zone, drag building toward the activation
 *               threshold (the moment the gesture "becomes real"). Avoids committing to an
 *               affordance animation on a stray tap.
 *  - COMMITTED — the drag satisfied the zone's completion rule; the action sink has already
 *                been notified.
 *  - INACTIVE — the drag crossed the threshold but the user reversed past the start before
 *               releasing (counts as a cancelled commit). Affordance animates back to the
 *               resting pose so the user can drag forward again in the same session.
 *  - ACTIVE   — the drag has crossed the activation threshold and is progressing toward
 *               commit. The affordance is in its "engaged" pose.
 *  - CANCELLED — the gesture failed its commit rule (released early, multi-touch, long-press
 *                without movement, rotation mid-drag). Terminal.
 */
enum class GestureState {
    GONE,
    TRACKING,
    ACTIVE,
    INACTIVE,
    COMMITTED,
    CANCELLED,
}
