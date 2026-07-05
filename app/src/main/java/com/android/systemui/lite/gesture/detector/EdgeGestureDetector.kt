package com.android.systemui.lite.gesture.detector

import android.content.Context
import android.provider.Settings
import com.android.systemui.lite.gesture.model.GestureSession
import com.android.systemui.lite.gesture.model.GestureState
import com.android.systemui.lite.gesture.model.GestureType
import com.android.systemui.lite.gesture.model.TouchZone
import com.android.systemui.lite.gesture.sink.GestureActionSink
import com.android.systemui.lite.gesture.zone.GestureZones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Recognises the horizontal back-swipe from the left or right screen edge. Extracted from the
 * original [com.android.systemui.lite.navigation.GestureHandler] during the Phase 2 refactor; the
 * state machine here is intentionally a faithful port — thresholds, the back-past-start
 * cancellation, the long-press guard and the navigation-mode gate all behave identically.
 *
 * Touch-up commit rule (mirrors AOSP):
 *  - session ACTIVE and horizontal drag past [commitThresholdPx] → COMMITTED, fires
 *    [GestureActionSink.onCommitted] with [GestureType.BACK].
 *  - any other state at release → CANCELLED, fires [GestureActionSink.onCancelled].
 */
class EdgeGestureDetector(
    private val navigationModeProvider: () -> Int,
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val edgeWidthPx: Float = 48f,
    private val commitThresholdPx: Float = 64f,
    private val longPressTimeoutMs: Long = 250L,
    private val resetDelayMs: Long = 60L,
    /** Re-read [Settings.Secure.NAVIGATION_MODE] on [refresh]. The provider is the indirection
     *  point that keeps this detector unit-testable without a real ContentResolver. */
    @Volatile override var sink: GestureActionSink,
    /** See [GestureDetector.onChanged]. */
    @Volatile override var onChanged: (GestureDetector) -> Unit = {},
) : GestureDetector {

    /**
     * Push a new session snapshot and notify observers. Every internal state change in this
     * detector MUST go through here so [onChanged] fires exactly once per mutation, regardless
     * of whether the change happened synchronously from a pointer callback or asynchronously
     * from a guard coroutine.
     */
    private fun publish(newSession: GestureSession?, newType: GestureType? = null) {
        _session.value = newSession
        if (newType != null || newSession == null) {
            _trackedType.value = newType ?: newSession?.let { typeFor(it) }
        }
        onChanged(this)
    }

    private fun typeFor(s: GestureSession): GestureType? = when (s.state) {
        GestureState.TRACKING -> if (s.zone == TouchZone.LEFT_EDGE || s.zone == TouchZone.RIGHT_EDGE) GestureType.BACK else null
        GestureState.COMMITTED -> when (s.zone) {
            TouchZone.LEFT_EDGE, TouchZone.RIGHT_EDGE -> GestureType.BACK
            TouchZone.BOTTOM -> GestureType.HOME
            else -> null
        }
        else -> null
    }

    private val _session = MutableStateFlow<GestureSession?>(null)
    override val session: StateFlow<GestureSession?> = _session.asStateFlow()

    private val _trackedType = MutableStateFlow<GestureType?>(null)
    override val trackedType: StateFlow<GestureType?> = _trackedType.asStateFlow()

    @Volatile private var navigationMode: Int = navigationModeProvider()
        private set

    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0

    private var totalDragX: Float = 0f
    private var totalDragY: Float = 0f
    private var hasMoved: Boolean = false

    private var longPressJob: Job? = null
    private var resetJob: Job? = null

    // --- GestureDetector --------------------------------------------------------

    override fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int) {
        if (navigationMode != MODE_GESTURE) return
        cancelTimeouts()
        val zone = GestureZones.detectZone(x, y, displayWidth, displayHeight, edgeWidthPx)
        if (zone != TouchZone.LEFT_EDGE && zone != TouchZone.RIGHT_EDGE) return
        if (GestureZones.isExcluded(x, y, displayWidth, displayHeight)) return

        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        totalDragX = 0f
        totalDragY = 0f
        hasMoved = false

        val now = System.currentTimeMillis()
        publish(GestureSession(
            zone = zone, startX = x, startY = y, startTimeMs = now,
            state = GestureState.TRACKING, progress = 0f,
        ), GestureType.BACK)
        sink.onProgress(GestureType.BACK, 0f)

        longPressJob = scope.launch {
            try {
                delay(longPressTimeoutMs)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            val current = _session.value
            if (current != null && !hasMoved && current.state == GestureState.TRACKING) {
                emitCancelled()
            }
        }
    }

    override fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float) {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return

        if (!hasMoved) {
            hasMoved = true
            longPressJob?.cancel()
            longPressJob = null
        }
        totalDragX += dx
        totalDragY += dy
        publish(handleMove(current, totalX))
    }

    override fun onUp() {
        cancelTimeouts()
        val current = _session.value ?: return
        if (current.state == GestureState.GONE ||
            current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) return

        val committed = (current.state == GestureState.TRACKING || current.state == GestureState.ACTIVE) &&
            abs(totalDragX) > commitThresholdPx && !reversedPastStart(current)
        if (committed) {
            publish(current.copy(state = GestureState.COMMITTED, progress = 1f), GestureType.BACK)
            sink.onCommitted(GestureType.BACK)
        } else {
            emitCancelled()
        }
        scheduleReset()
    }

    override fun cancel() {
        cancelTimeouts()
        emitCancelled()
        scheduleReset()
    }

    override fun refresh() {
        navigationMode = readNavigationMode()
    }

    override fun updateDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    // --- state machine ----------------------------------------------------------

    private fun handleMove(current: GestureSession, totalX: Float): GestureSession {
        if (!GestureZones.isEdgeGesture(totalDragX, totalDragY)) {
            // Vertical-ish drag: keep tracking so the gesture can go horizontal again.
            return current.copy(progress = computeProgress(totalDragX))
        }
        val pastThreshold = abs(totalDragX) > commitThresholdPx
        val reversed = reversedPastStart(current)
        return when (current.state) {
            GestureState.TRACKING -> {
                when {
                    reversed && pastThreshold -> current.copy(state = GestureState.INACTIVE, progress = computeProgress(totalDragX))
                    pastThreshold -> current.copy(state = GestureState.ACTIVE, progress = computeProgress(totalDragX))
                    else -> current.copy(progress = computeProgress(totalDragX))
                }
            }
            GestureState.ACTIVE -> {
                if (reversed && pastThreshold) current.copy(state = GestureState.INACTIVE, progress = computeProgress(totalDragX))
                else current.copy(progress = computeProgress(totalDragX))
            }
            GestureState.INACTIVE -> {
                if (!reversed && pastThreshold) current.copy(state = GestureState.ACTIVE, progress = computeProgress(totalDragX))
                else current.copy(progress = computeProgress(totalDragX))
            }
            else -> current.copy(progress = computeProgress(totalDragX))
        }
    }

    private fun reversedPastStart(current: GestureSession): Boolean = when (current.zone) {
        TouchZone.LEFT_EDGE -> totalDragX < -commitThresholdPx
        TouchZone.RIGHT_EDGE -> totalDragX > commitThresholdPx
        else -> false
    }

    private fun computeProgress(totalDragX: Float): Float {
        val denom = (displayWidth / 2f).coerceAtLeast(1f)
        return (abs(totalDragX) / denom).coerceIn(0f, 1f)
    }

    // --- helpers ----------------------------------------------------------------

    private fun emitCancelled() {
        val current = _session.value
        if (current != null &&
            current.state != GestureState.GONE &&
            current.state != GestureState.COMMITTED) {
            publish(current.copy(state = GestureState.CANCELLED, progress = 0f))
            _trackedType.value?.let { sink.onCancelled(it) }
        }
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            try {
                delay(resetDelayMs)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            totalDragX = 0f; totalDragY = 0f; hasMoved = false
            publish(null)
        }
    }

    private fun cancelTimeouts() {
        longPressJob?.cancel(); longPressJob = null
        resetJob?.cancel(); resetJob = null
    }

    private fun readNavigationMode(): Int = try {
        Settings.Secure.getInt(context?.contentResolver, SETTINGS_NAVIGATION_MODE, MODE_BUTTON)
    } catch (_: Exception) {
        navigationModeProvider()
    }

    companion object {
        const val MODE_BUTTON = 0
        const val MODE_GESTURE = 2
        private const val SETTINGS_NAVIGATION_MODE = "navigation_mode"
    }
}
