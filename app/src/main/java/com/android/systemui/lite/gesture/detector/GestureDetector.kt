package com.android.systemui.lite.gesture.detector

import com.android.systemui.lite.gesture.model.GestureSession
import com.android.systemui.lite.gesture.model.GestureType
import com.android.systemui.lite.gesture.sink.GestureActionSink
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-zone gesture recogniser. Each detector owns the state machine for exactly one screen
 * region — edge strips (back), bottom strip (home/recents), or the top strip (shade). The host
 * routes a touch-down to the right detector by first classifying the coordinate with
 * [com.android.systemui.lite.gesture.zone.GestureZones].
 *
 * Public surface:
 *  - [session] / [trackedType] — read by Compose affordances via `collectAsState()`;
 *  - [onDown] / [onMove] / [onUp] — called by the pointer-input handler of the owning overlay;
 *  - [cancel] / [resetSession] — lifecycle hooks the host calls on rotation or multi-touch.
 *
 * A detector MUST be safe to call from the main thread only. The [scope] it uses internally is
 * Main-immediate, so guard-runnable coroutines post back and never block the caller.
 */
interface GestureDetector {

    /** Current session, or GONE. Compose affordances collectAsState() on this. */
    val session: StateFlow<GestureSession?>

    /**
     * The semantic gesture being tracked for the active session, or null when no session is
     * progressing (BACK for an edge session, HOME/RECENTS once the release rule has run, etc.).
     * Used by the affordance to choose which visual to render.
     */
    val trackedType: StateFlow<GestureType?>

    /** Reset hook the host can use to change the dispatch sink at runtime (Koin rewiring). */
    var sink: GestureActionSink

    /**
     * Observer invoked every time this detector mutates [session] or [trackedType] — whether
     * synchronously from an [onDown]/[onMove]/[onUp] call or asynchronously from a guard
     * coroutine. Hosts (typically a pipeline / facade) re-merge their own snapshot of the
     * world STATE from this.
     *
     * Default is a no-op so callers that don't need live re-merging (pipeline, tests) don't
     * have to set it.
     */
    var onChanged: (GestureDetector) -> Unit

    // --- pointer lifecycle -------------------------------------------------------

    /**
     * Classify and maybe begin a session. Coordinates are display-space px. Returns void — the
     * caller should then read [session] to know whether this detector claimed the gesture.
     */
    fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int)

    /**
     * Advance the active session with the latest per-frame delta plus the accumulated drag
     * totals since [onDown]. If the detector has not claimed the gesture this is a no-op.
     */
    fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float)

    /**
     * Release: run the commit rule, fire the sink, schedule the reset-to-GONE.
     */
    fun onUp()

    /** Cancel without committing (multi-touch, configuration change, etc.). */
    fun cancel()

    /** Re-read dynamic state the detector depends on (navigation mode, screen geometry). */
    fun refresh()

    /** Update the cached display geometry. */
    fun updateDisplaySize(width: Int, height: Int)
}
