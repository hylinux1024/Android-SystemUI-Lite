package com.android.systemui.lite.navigation

import android.content.Context
import android.provider.Settings
import com.android.systemui.lite.model.GestureSession
import com.android.systemui.lite.model.GestureState
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.TouchZone
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
 * Stateful gesture state machine. Track a gesture from touch-down through move to release,
 * validate it over time, and emit state + progress so a visual affordance and a key-event
 * action can react.
 *
 * Construction reads [Settings.Secure.NAVIGATION_MODE] so edge gestures are guarded to
 * gesture nav (mode == 2); call [refreshNavigationMode] on configuration change to re-read.
 *
 * Only the pure inputs and the mutable state live here — there is no Compose, no Context
 * in the state transitions themselves, so the logic is unit-testable on the JVM.
 */
class GestureHandler(
    /** Invoked with the committed [GestureType] so the caller can dispatch the matching key
     *  event (BACK / HOME / RECENTS). Non-null only when a gesture actually commits.
     *  The singleton is constructed in Koin with a no-op default; the startable rewires
     *  the live [onAction] hook after obtaining the instance (US-007 AC3). */
    onActionInit: (GestureType) -> Unit,
    /** Android context used to read [Settings.Secure.NAVIGATION_MODE]. In unit tests pass a
     *  fake/noop content resolver and override [navigationModeProvider] to control the mode. */
    private val context: Context? = null,
    /** Override the navigation-mode integer reader: 0 = 3-button, 2 = gestures. When null,
     *  falls back to reading [Settings.Secure.NAVIGATION_MODE] from [context]. In test, set this
     *  to a controllable lambda so the guard path is exercised without a real content resolver. */
    navigationModeProvider: (() -> Int)? = null,
    /** Coroutine scope used for the long-press guard and post-commit reset delay. Defaults to
     *  a scope on [Dispatchers.Main] (suitable from a Compose UI); in unit tests pass a scope
     *  backed by `TestScope` from kotlinx-coroutines-test to control virtual time. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Edge strip width (px) used during [onDown]. Mirrors the constant used by [GestureZones]. */
    private val edgeWidthPx: Float = 48f,
    /** Static distance (px) a horizontal drag must exceed before ENTRY -> ACTIVE. */
    private val commitThresholdPx: Float = 64f,
    /** Upward bottom-swipe distance (px) that satisfies the home gesture. */
    private val homeSwipeDp: Float = 80f,
    /** Upward bottom-swipe distance (px) that satisfies the recents gesture. */
    private val recentsSwipeDp: Float = 150f,
    /** Long-press window (ms). Mirrors AOS [android.view.ViewConfiguration.getLongPressTimeout]. */
    private val longPressTimeoutMs: Long = 250L,
    /** Density used to convert dp thresholds to px. Production passes the device density; tests
     *  may pass 1.0 and provide thresholds in px. */
    private val density: Float = 1f,
    /** Delay (ms) after COMMITTED / CANCELLED before the session is reset to GONE. */
    private val resetDelayMs: Long = 60L
) {
    /** Current session. Null when the handler is in [GestureState.GONE]. Compose can collectAsState(). */
    private val _session = MutableStateFlow<GestureSession?>(null)
    val session: StateFlow<GestureSession?> = _session.asStateFlow()

    /** Gesture type being tracked for the visual affordance so it renders the correct arrow. */
    private val _trackedType = MutableStateFlow<GestureType?>(null)
    val trackedType: StateFlow<GestureType?> = _trackedType.asStateFlow()

    /**
     * Live dispatch hook for a committed [GestureType]. Initialized from [onActionInit] at
     * construction (Koin passes a no-op) and rewired by the startable to the real key-event
     * dispatch once the singleton is obtained (US-007 AC3). Marked [Volatile] so the rewire is
     * visible to the gesture coroutine immediately.
     */
    @Volatile
    var onAction: (GestureType) -> Unit = onActionInit

    /** Whether the device is currently in gesture-nav mode. Re-read on [refreshNavigationMode]. */
    @Volatile
    var navigationMode: Int = navigationModeProvider?.invoke() ?: readNavigationModeFromSettings()
        private set

    private val homeSwipePx get() = homeSwipeDp * density
    private val recentsSwipePx get() = recentsSwipeDp * density

    /** Display size in px, captured at [onDown] so progress and thresholds can be computed. */
    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0

    /** Last-tracked move totals so we know how far the user has dragged and which way. */
    private var totalDragX: Float = 0f
    private var totalDragY: Float = 0f
    private var hasMoved: Boolean = false

    private var longPressJob: Job? = null
    private var resetJob: Job? = null

    /**
     * Handler for a touch-down. Classifies the touch into a [TouchZone]; if the zone is an
     * edge and the touch is outside the exclusion area and gesture nav is enabled, the session
     * starts in [GestureState.ENTRY] with [GestureType] tracked appropriately.
     */
    fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int) {
        // Gesture nav mode must be on (mode == 2) — edge back is suppressed in 3-button mode.
        if (navigationMode != RESET_MODE_ON) return
        // Bottom-zone home/recents gestures are still exposed regardless of mode in stock AOS,
        // but the PRD scopes bottom gestures to gesture mode too for parity with NavigationBarView.
        cancelTimeouts()
        val zone = GestureZones.detectZone(x, y, displayWidth, displayHeight, edgeWidthPx)
        if (zone == TouchZone.NONE) {
            return
        }
        if (zone == TouchZone.LEFT_EDGE || zone == TouchZone.RIGHT_EDGE) {
            if (GestureZones.isExcluded(x, y, displayWidth, displayHeight)) {
                return
            }
        }
        // Capture geometry for progress math.
        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        totalDragX = 0f
        totalDragY = 0f
        hasMoved = false

        val type = when (zone) {
            TouchZone.LEFT_EDGE, TouchZone.RIGHT_EDGE -> GestureType.BACK
            else -> null // HOME/RECENTS decided at release time based on distance
        }
        val now = System.currentTimeMillis()
        val initial = GestureSession(
            zone = zone,
            startX = x,
            startY = y,
            startTimeMs = now,
            state = GestureState.ENTRY,
            progress = 0f
        )
        _session.value = initial
        _trackedType.value = type

        // Post the long-press guard. If no move has occurred by longPressTimeoutMs the session
        // is treated as a tap (no-op) and cancelled.
        longPressJob = scope.launch {
            delay(longPressTimeoutMs)
            val current = _session.value
            if (current != null && !hasMoved && current.state == GestureState.ENTRY) {
                emitCancelled()
            }
        }
    }

    /**
     * Handler for a touch-move. Transitions ENTRY -> ACTIVE once the drag passes the static
     * commit threshold; flips ACTIVE -> INACTIVE when the user reverses direction past the
     * start; computes progress 0..1 for the back indicator. Multi-touch cancellation is driven
     * by the caller via [cancelOnMultiTouch].
     */
    fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float) {
        val current = _session.value ?: return
        if (current.state == GestureState.GONE || current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) {
            return
        }
        // Cancel the long-press guard on first real movement.
        if (!hasMoved) {
            hasMoved = true
            longPressJob?.cancel(); longPressJob = null
        }
        totalDragX += dx
        totalDragY += dy
        _session.value = when (current.zone) {
            TouchZone.LEFT_EDGE, TouchZone.RIGHT_EDGE -> handleEdgeMove(current, totalX)
            else -> current // bottom-zone moves are ignored until release
        }
    }

    /** Handle horizontal edge-swipe transitions. */
    private fun handleEdgeMove(current: GestureSession, totalX: Float): GestureSession {
        val pastThreshold = abs(totalDragX) > commitThresholdPx
        if (!GestureZones.isEdgeGesture(totalDragX, totalDragY)) {
            // Vertical-ish drag — do not promote to active, but keep entry alive so the gesture
            // can still go horizontal.
            return current
        }
        return when (current.state) {
            GestureState.ENTRY -> {
                if (pastThreshold) {
                    current.copy(state = GestureState.ACTIVE, progress = computeProgress(totalDragX))
                } else {
                    current.copy(progress = computeProgress(totalDragX))
                }
            }
            GestureState.ACTIVE -> {
                // User dragged back past start on a back-gesture -> INACTIVE (commit cancelled).
                val backPastStart = when (current.zone) {
                    TouchZone.LEFT_EDGE -> totalDragX < -commitThresholdPx
                    TouchZone.RIGHT_EDGE -> totalDragX > commitThresholdPx
                    else -> false
                }
                if (backPastStart) {
                    current.copy(state = GestureState.INACTIVE, progress = computeProgress(totalDragX))
                } else {
                    current.copy(progress = computeProgress(totalDragX))
                }
            }
            else -> current
        }
    }

    /**
     * Compute 0..1 progress for the back indicator. Linear with drag distance up to half the
     * display width — past that we clamp at 1.
     */
    private fun computeProgress(totalDragX: Float): Float {
        val denom = (displayWidth / 2f).coerceAtLeast(1f)
        return (abs(totalDragX) / denom).coerceIn(0f, 1f)
    }

    /**
     * Handler for touch-up. Decides whether to commit:
     *  - Edge session ACTIVE past threshold -> COMMITTED + [onAction](GestureType.BACK).
     *  - Edge session INACTIVE or not yet past threshold -> CANCELLED.
     *  - Bottom session -> classify by upward distance: > recentsSwipe -> RECENTS,
     *    > homeSwipe -> HOME, otherwise CANCELLED.
     * Always schedules a reset to GONE after [resetDelayMs].
     */
    fun onUp() {
        cancelTimeouts()
        val current = _session.value ?: return
        if (current.state == GestureState.GONE || current.state == GestureState.COMMITTED ||
            current.state == GestureState.CANCELLED) {
            return
        }
        when (current.zone) {
            TouchZone.LEFT_EDGE, TouchZone.RIGHT_EDGE -> resolveEdgeUp(current)
            else -> resolveBottomUp(current)
        }
        // Reset to GONE shortly after the terminal state so the visual affordance can fade out.
        scheduleReset()
    }

    private fun resolveEdgeUp(current: GestureSession) {
        if (current.state == GestureState.ACTIVE && abs(totalDragX) > commitThresholdPx) {
            val committed = current.copy(state = GestureState.COMMITTED, progress = 1f)
            _session.value = committed
            _trackedType.value = GestureType.BACK
            onAction(GestureType.BACK)
        } else {
            emitCancelled()
        }
    }

    private fun resolveBottomUp(current: GestureSession) {
        // dy negative in Android coords = upward drag. Accept only upward drags.
        val upDy = -totalDragY
        when {
            upDy >= recentsSwipePx -> commitBottom(current, GestureType.RECENTS)
            upDy >= homeSwipePx -> commitBottom(current, GestureType.HOME)
            else -> emitCancelled()
        }
    }

    private fun commitBottom(current: GestureSession, type: GestureType) {
        val committed = current.copy(state = GestureState.COMMITTED, progress = 1f)
        _session.value = committed
        _trackedType.value = type
        onAction(type)
    }

    /** Cancel the in-flight session immediately (used when the caller detects multi-touch). */
    fun cancelOnMultiTouch() {
        cancelTimeouts()
        emitCancelled()
        scheduleReset()
    }

    /** Re-read navigation mode from settings so a runtime nav-mode flip is honored. */
    fun refreshNavigationMode() {
        navigationMode = readNavigationModeFromSettings()
    }

    /**
     * Update the cached display geometry. Called from [onConfigurationChanged] in the host so
     * that a gesture in flight during rotation keeps correct thresholds, and so the very next
     * drag after rotation begins from the right width/height even if no new onDown occurs.
     */
    fun updateDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    /**
     * Cancel any in-flight session immediately. Called from [onConfigurationChanged] so a gesture
     * dragged partway through a rotation never mis-fires with stale geometry (US-007 AC4).
     */
    fun resetSession() {
        cancelTimeouts()
        emitCancelled()
        scheduleReset()
    }

    /** Bottom-zone gesture detection hook — the PRD wires BOTTOM zone handling at release. */
    @Suppress("unused")
    @JvmField
    val bottomZoneEnabled: Boolean = true

    private fun emitCancelled() {
        val current = _session.value
        if (current != null && current.state != GestureState.GONE && current.state != GestureState.COMMITTED) {
            _session.value = current.copy(state = GestureState.CANCELLED, progress = 0f)
        }
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(resetDelayMs)
            _session.value = null
            _trackedType.value = null
            totalDragX = 0f; totalDragY = 0f; hasMoved = false
        }
    }

    private fun cancelTimeouts() {
        longPressJob?.cancel(); longPressJob = null
        resetJob?.cancel(); resetJob = null
    }

    private fun readNavigationModeFromSettings(): Int {
        return try {
            Settings.Secure.getInt(
                context?.contentResolver, SETTINGS_NAVIGATION_MODE, RESET_MODE_OFF
            )
        } catch (e: Exception) {
            RESET_MODE_OFF
        }
    }

    companion object {
        const val RESET_MODE_OFF = 0
        const val RESET_MODE_ON = 2
        const val LONG_PRESS_TIMEOUT_MS = 250L
        private const val SETTINGS_NAVIGATION_MODE = "navigation_mode"
    }
}
