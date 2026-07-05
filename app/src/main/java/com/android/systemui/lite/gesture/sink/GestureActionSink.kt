package com.android.systemui.lite.gesture.sink

import com.android.systemui.lite.gesture.model.GestureType

/**
 * The business side of a gesture. The gesture package knows nothing about *how* a committed
 * BACK, HOME, RECENTS or SHADE is realised — it just reports the semantic outcome here; the
 * host (typically [com.android.systemui.lite.core.NavigationBarCoreStartable]) decides whether
 * that means a keyevent, launching the recents panel, or pulling the shade.
 *
 * Implementations MUST be safe to call from the gesture detector coroutine scope (a
 * Main-immediate dispatcher): just post to the main thread or mutate thread-safe state.
 *
 * ### Lifecycle contract
 *  - each committed gesture is reported exactly once;
 *  - `onCommitted` is the last meaningful callback for that session (the detector moves to
 *    [com.android.systemui.lite.gesture.model.GestureState.COMMITTED] and shortly after to GONE);
 *  - `onCancelled` may fire after a previous `onProgress` for that session, signalling that a
 *    gesture-in-flight did NOT complete — the host should tear down any half-shown UI.
 */
interface GestureActionSink {

    /** A gesture met its commit rule. [type] is the semantic outcome. */
    fun onCommitted(type: GestureType)

    /** A gesture that had been tracking never completed. */
    fun onCancelled(type: GestureType)

    /**
     * Continuous progress update while the gesture is tracked. Only fired by detectors that
     * have a meaningful per-frame progress semantic — today that is solely SHADE, where
     * `progress` 0..1 maps directly to the shade-open fraction. BACK / HOME / RECENTS do not
     * call this (their decision happens at [onCommitted]).
     */
    fun onProgress(type: GestureType, progress: Float)
}
