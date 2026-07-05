package com.android.systemui.lite.gesture.detector

import com.android.systemui.lite.gesture.model.GestureSession
import com.android.systemui.lite.gesture.model.GestureState
import com.android.systemui.lite.gesture.model.GestureType
import com.android.systemui.lite.gesture.model.TouchZone
import com.android.systemui.lite.gesture.sink.GestureActionSink
import com.android.systemui.lite.gesture.zone.GestureZones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recognises the upward swipe from the bottom of the screen — short swipe = HOME, longer swipe
 * with a brief hold = RECENTS. Extracted from the original
 * [com.android.systemui.lite.navigation.GestureHandler] during Phase 2.
 *
 * Decision happens at release time only (there is no mid-drag commit): the detector tracks the
 * session state from ENTRY through ACTIVE and only resolves HOME vs RECENTS vs CANCEL in
 * [onUp]. This preserves the original behaviour so existing tests keep their semantics.
 *
 * Vertical progress is reported continuously via [GestureActionSink.onProgress] while tracking,
 * (the bottom-handle affordance reads the session's [GestureSession.progress]).
 */
class BottomGestureDetector(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val edgeWidthPx: Float = 48f,
    private val homeSwipeDp: Float = 80f,
    private val recentsSwipeDp: Float = 150f,
    private val density: Float = 1f,
    private val resetDelayMs: Long = 60L,
    @Volatile override var sink: GestureActionSink,
    @Volatile override var onChanged: (GestureDetector) -> Unit = {},
) : GestureDetector {

    private val _session = MutableStateFlow<GestureSession?>(null)
    override val session: StateFlow<GestureSession?> = _session.asStateFlow()

    private val _trackedType = MutableStateFlow<GestureType?>(null)
    override val trackedType: StateFlow<GestureType?> = _trackedType.asStateFlow()

    private fun publish(newSession: GestureSession?, newType: GestureType? = null) {
        _session.value = newSession
        _trackedType.value = newType ?: newSession?.let { typeFor(it) }
        onChanged(this)
    }

    private fun typeFor(s: GestureSession): GestureType? = when (s.state) {
        GestureState.TRACKING -> null
        GestureState.COMMITTED -> when (s.zone) {
            TouchZone.BOTTOM -> GestureType.HOME
            else -> null
        }
        else -> null
    }

    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0

    private var totalDragX: Float = 0f
    private var totalDragY: Float = 0f

    private val homeSwipePx get() = homeSwipeDp * density
    private val recentsSwipePx get() = recentsSwipeDp * density

    // --- GestureDetector --------------------------------------------------------

    override fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int) {
        val zone = GestureZones.detectZone(x, y, displayWidth, displayHeight, edgeWidthPx)
        if (zone != TouchZone.BOTTOM) return

        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        totalDragX = 0f
        totalDragY = 0f

        val now = System.currentTimeMillis()
        publish(GestureSession(
            zone = TouchZone.BOTTOM, startX = x, startY = y, startTimeMs = now,
            state = GestureState.TRACKING, progress = 0f,
        ))
    }

    override fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float) {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return
        totalDragX += dx
        totalDragY += dy

        // Progress tracks how far the finger has climbed (negative dy = upward) relative to
        // the recents swipe distance — clamps at 1.0 so the affordance reaches full size.
        val upDy = -totalDragY
        val progress = (upDy / recentsSwipePx).coerceIn(0f, 1f)
        publish(current.copy(state = GestureState.TRACKING, progress = progress))
        _trackedType.value?.let { sink.onProgress(it, progress) }
    }

    override fun onUp() {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return

        val upDy = -totalDragY
        when {
            upDy >= recentsSwipePx -> commit(GestureType.RECENTS)
            upDy >= homeSwipePx -> commit(GestureType.HOME)
            else -> {
                publish(current.copy(state = GestureState.CANCELLED, progress = 0f))
                sink.onCancelled(GestureType.HOME)
            }
        }
    }

    override fun cancel() {
        val current = _session.value
        if (current != null &&
            current.state != GestureState.GONE &&
            current.state != GestureState.COMMITTED) {
            publish(current.copy(state = GestureState.CANCELLED, progress = 0f))
            sink.onCancelled(_trackedType.value ?: GestureType.HOME)
        }
        totalDragX = 0f; totalDragY = 0f
        publish(null)
    }

    override fun refresh() { /* bottom gesture has no mode gate to refresh */ }

    override fun updateDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    // --- helpers ----------------------------------------------------------------

    private fun commit(type: GestureType) {
        val current = _session.value ?: return
        publish(current.copy(state = GestureState.COMMITTED, progress = 1f), type)
        sink.onCommitted(type)
    }
}
