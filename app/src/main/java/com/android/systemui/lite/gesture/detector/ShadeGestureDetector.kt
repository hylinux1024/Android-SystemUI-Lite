package com.android.systemui.lite.gesture.detector

import com.android.systemui.lite.gesture.model.GestureSession
import com.android.systemui.lite.gesture.model.GestureState
import com.android.systemui.lite.gesture.model.GestureType
import com.android.systemui.lite.gesture.model.TouchZone
import com.android.systemui.lite.gesture.sink.GestureActionSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recognises the top-of-screen pull-down that opens the notification shade. Extracted from the
 * inline `detectDragGestures` block in `StatusBar` during Phase 3 of the gesture refactor.
 *
 * ### Behavior (faithful to the pre-Phase-3 version)
 *  - onDown → unconditionally begin a [GestureState.TRACKING] session (the TYPE_STATUS_BAR
 *    window's own band has already classified the touch before it reaches us).
 *  - onMove while the finger is climbing → emit [GestureActionSink.onProgress] with a 0..1
 *    shade-open fraction. `StatusBar` reads the same fraction from [session].progress.
 *  - onUp decodes the gesture:
 *      - |drag| < [slopPx] → **tap** — call [GestureActionSink.onCommitted] so the host can
 *        toggle the shade (open when closed, close when open);
 *      - |drag| >= [flingVelocityPxMs] && [flingDistancePx] → fling-open → **committed**;
 *      - otherwise → small non-fling drag → **cancelled**, shade stays put.
 *
 * The detector lives behind the status-bar window: touches that begin in the narrow status-bar
 * band but land on the shade content (after pull-down) are handled by the shade's own scrim, not
 * by this detector.
 */
class ShadeGestureDetector(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Distance the shade must travel to be considered "open" (≈ full display height). The
     *  same value StatusBar passes to [com.android.systemui.lite.core.ShadeController.flingShade]. */
    private val shadeRangePx: Float,
    private val slopPx: Float = 8f,
    private val flingVelocityPxMs: Float = 800f,
    private val flingDistancePx: Float = 40f,
    private val openThresholdFraction: Float = 1f / 3f,
    @Volatile override var sink: GestureActionSink,
    @Volatile override var onChanged: (GestureDetector) -> Unit = {},
) : GestureDetector {

    private companion object {
        /** Minimum total downward travel (px) for a quick-fling to commit an open. Distinct
         *  from [flingDistancePx] (which only gates the velocity check). */
        private const val MIN_FLING_TRAVEL_PX = 120f
    }

    private val _session = MutableStateFlow<GestureSession?>(null)
    override val session: StateFlow<GestureSession?> = _session.asStateFlow()

    private val _trackedType = MutableStateFlow<GestureType?>(null)
    override val trackedType: StateFlow<GestureType?> = _trackedType.asStateFlow()

    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0

    private var totalDragY: Float = 0f
    private var totalDragX: Float = 0f
    private var dragStartMs: Long = 0L
    private var dragging: Boolean = false

    private fun publish(newSession: GestureSession?, newType: GestureType? = null) {
        _session.value = newSession
        _trackedType.value = newType
        onChanged(this)
    }

    private fun zoneForThisStrip(): TouchZone = TouchZone.TOP

    // --- GestureDetector --------------------------------------------------------

    override fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int) {
        // pointerInput is installed on the TYPE_STATUS_BAR window (the thin 28dp strip at the
        // top of the display), so any onDown we receive is already inside the status-bar band
        // and should be claimed unconditionally.
        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        totalDragY = 0f; totalDragX = 0f
        dragStartMs = System.currentTimeMillis()
        dragging = false

        val now = dragStartMs
        publish(GestureSession(
            zone = TouchZone.TOP, startX = x, startY = y, startTimeMs = now,
            state = GestureState.TRACKING, progress = 0f,
        ))
    }

    override fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float) {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return
        totalDragY += dy
        totalDragX += dx
        if (!dragging && (totalDragY > slopPx || totalDragX > slopPx)) dragging = true

        val upDy = totalDragY
        if (upDy <= 0f) {
            // Fingers went upward (or not at all) — reset progress to 0 to match old behavior.
            publish(current.copy(progress = 0f))
            return
        }
        // Map drag distance to a shade-open fraction. Setting the session's progress directly
        // means the StatusBar's y-offset animation can read the same value.
        val fraction = (upDy / shadeRangePx).coerceIn(0f, 1f)
        publish(current.copy(progress = fraction))
        sink.onProgress(GestureType.SHADE, fraction)
    }

    override fun onUp() {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return

        val absY = kotlin.math.abs(totalDragY)
        val absX = kotlin.math.abs(totalDragX)

        if (absY < slopPx && absX < slopPx) {
            // Tap: toggle the shade.
            publish(current.copy(state = GestureState.COMMITTED, progress = current.progress), GestureType.SHADE)
            sink.onCommitted(GestureType.SHADE)
            reset()
            return
        }

        val elapsedMs = (System.currentTimeMillis() - dragStartMs).coerceAtLeast(1L)
        val velocity = totalDragY / elapsedMs * 1000f
        val isFling = velocity > flingVelocityPxMs && totalDragY > flingDistancePx
        val shouldOpen = if (isFling) totalDragY > MIN_FLING_TRAVEL_PX
            else totalDragY > shadeRangePx * openThresholdFraction

        if (shouldOpen || current.progress > 0.5f) {
            publish(current.copy(state = GestureState.COMMITTED, progress = 1f), GestureType.SHADE)
            sink.onCommitted(GestureType.SHADE)
        } else {
            publish(current.copy(state = GestureState.CANCELLED, progress = 0f), GestureType.SHADE)
            sink.onCancelled(GestureType.SHADE)
        }
        reset()
    }

    override fun cancel() {
        val current = _session.value
        if (current != null &&
            current.state != GestureState.GONE &&
            current.state != GestureState.COMMITTED) {
            publish(current.copy(state = GestureState.CANCELLED, progress = 0f))
            sink.onCancelled(_trackedType.value ?: GestureType.SHADE)
        }
        reset()
    }

    override fun refresh() { /* no dynamic state to refresh */ }

    override fun updateDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    // --- helpers ----------------------------------------------------------------

    private fun reset() {
        totalDragY = 0f; totalDragX = 0f; dragging = false
        publish(null)
    }
}
