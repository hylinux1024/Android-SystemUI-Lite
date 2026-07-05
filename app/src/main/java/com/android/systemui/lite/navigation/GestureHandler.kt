package com.android.systemui.lite.navigation

import android.content.Context
import android.provider.Settings
import com.android.systemui.lite.gesture.detector.BottomGestureDetector
import com.android.systemui.lite.gesture.detector.EdgeGestureDetector
import com.android.systemui.lite.gesture.detector.GestureDetector
import com.android.systemui.lite.gesture.model.GestureSession as NewSession
import com.android.systemui.lite.gesture.model.GestureState as NewState
import com.android.systemui.lite.gesture.model.GestureType as NewType
import com.android.systemui.lite.gesture.model.TouchZone as NewZone
import com.android.systemui.lite.gesture.sink.GestureActionSink
import com.android.systemui.lite.model.GestureSession
import com.android.systemui.lite.model.GestureState
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.TouchZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Process-wide gesture state machine — now a thin facade over the per-zone detectors
 * [EdgeGestureDetector] and [BottomGestureDetector]. The public surface (constructor
 * parameters, [session], [trackedType], [onAction], [onDown] / [onMove] / [onUp],
 * [cancelOnMultiTouch], [refreshNavigationMode], [updateDisplaySize], [resetSession]) is
 * intentionally unchanged so the existing callers in [NavigationBarCoreStartable] and the
 * gesture overlay composables keep working while the real logic lives in the new detectors.
 *
 * ### What this class still owns
 *  - the two detector singletons, constructed with the same constructor parameters the old
 *    handler used (thresholds, dp→px conversion, navigation-mode provider, coroutine scope);
 *  - the legacy [onAction] hook the startable rewires after Koin construction;
 *  - the merged [session] / [trackedType] StateFlows that the old affordances read.
 *
 * ### What moved to the detectors
 *  - the edge state machine (ENTRY→ACTIVE→INACTIVE→COMMITTED/CANCELLED) → [EdgeGestureDetector];
 *  - the bottom-zone distance classification (HOME vs RECENTS) → [BottomGestureDetector];
 *  - the long-press guard, the navigation-mode gate, the reset-to-GONE delay.
 *
 * The new detectors emit the new model types ([NewState], [NewType], [NewZone], [NewSession])
 * which this facade maps back to the legacy model types on every emission. The mapping is
 * lossless for the states the legacy UI actually reads (GONE, ENTRY, ACTIVE, INACTIVE,
 * COMMITTED, CANCELLED).
 *
 * **Migration note:** this facade exists for one phase only. Phase 4 removes it and wires the
 * detectors directly into [com.android.systemui.lite.gesture.GesturePipeline].
 */
class GestureHandler(
    onActionInit: (GestureType) -> Unit,
    private val context: Context? = null,
    navigationModeProvider: (() -> Int)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val edgeWidthPx: Float = 48f,
    private val commitThresholdPx: Float = 64f,
    private val homeSwipeDp: Float = 80f,
    private val recentsSwipeDp: Float = 150f,
    private val longPressTimeoutMs: Long = 250L,
    private val density: Float = 1f,
    private val resetDelayMs: Long = 60L,
) {
    // --- merged legacy StateFlows (what the old UI reads) -------------------------

    private val _session = MutableStateFlow<GestureSession?>(null)
    val session: StateFlow<GestureSession?> = _session.asStateFlow()

    private val _trackedType = MutableStateFlow<GestureType?>(null)
    val trackedType: StateFlow<GestureType?> = _trackedType.asStateFlow()

    @Volatile
    var onAction: (GestureType) -> Unit = onActionInit

    @Volatile
    var navigationMode: Int = navigationModeProvider?.invoke() ?: readNavigationModeFromSettings()
        private set

    @Volatile private var displayWidth: Int = 0
    @Volatile private var displayHeight: Int = 0

    // Snapshot of each detector's current session — refreshed both synchronously after each
    // pointer event AND from each detector's [GestureDetector.onChanged] callback (so async
    // guard / reset coroutines that mutate their detector's session propagate to this facade).
    @Volatile private var edgeSession: NewSession? = null
    @Volatile private var bottomSession: NewSession? = null

    // --- the new detectors -------------------------------------------------------

    private val sink = object : GestureActionSink {
        override fun onCommitted(type: NewType) {
            this@GestureHandler.onAction(type.toLegacy())
        }
        override fun onCancelled(type: NewType) { /* legacy onAction has no cancel hook */ }
        override fun onProgress(type: NewType, progress: Float) { /* legacy UI reads session.progress */ }
    }

    private val modeProvider: () -> Int = navigationModeProvider ?: { readNavigationModeFromSettings() }

    private val edgeDetector: EdgeGestureDetector = EdgeGestureDetector(
        navigationModeProvider = modeProvider,
        context = context,
        scope = scope,
        edgeWidthPx = edgeWidthPx,
        commitThresholdPx = commitThresholdPx,
        longPressTimeoutMs = longPressTimeoutMs,
        resetDelayMs = resetDelayMs,
        sink = sink,
        onChanged = { triggerReMerge() },
    )

    private val bottomDetector: BottomGestureDetector = BottomGestureDetector(
        scope = scope,
        edgeWidthPx = edgeWidthPx,
        homeSwipeDp = homeSwipeDp,
        recentsSwipeDp = recentsSwipeDp,
        density = density,
        resetDelayMs = resetDelayMs,
        sink = sink,
        onChanged = { triggerReMerge() },
    )

    // --- public API (unchanged from the pre-refactor surface) --------------------

    fun onDown(x: Float, y: Float, displayWidth: Int, displayHeight: Int) {
        this.displayWidth = displayWidth
        this.displayHeight = displayHeight
        edgeDetector.onDown(x, y, displayWidth, displayHeight)
        bottomDetector.onDown(x, y, displayWidth, displayHeight)
        syncAfterEvent()
    }

    fun onMove(dx: Float, dy: Float, totalX: Float, totalY: Float) {
        edgeDetector.onMove(dx, dy, totalX, totalY)
        bottomDetector.onMove(dx, dy, totalX, totalY)
        syncAfterEvent()
    }

    fun onUp() {
        edgeDetector.onUp()
        bottomDetector.onUp()
        syncAfterEvent()
    }

    fun cancelOnMultiTouch() {
        edgeDetector.cancel()
        bottomDetector.cancel()
        syncAfterEvent()
    }

    fun refreshNavigationMode() {
        navigationMode = modeProvider()
        edgeDetector.refresh()
    }

    fun updateDisplaySize(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        edgeDetector.updateDisplaySize(width, height)
        bottomDetector.updateDisplaySize(width, height)
    }

    fun resetSession() {
        edgeDetector.cancel()
        bottomDetector.cancel()
        syncAfterEvent()
    }

    // --- internals ---------------------------------------------------------------

    /**
     * Called from two paths:
     *  1. directly by [onDown]/[onMove]/[onUp]/[resetSession]/[cancelOnMultiTouch] (sync);
     *  2. indirectly via [detectorObserver] whenever a detector mutates its own session
     *     (async — long-press guard, post-commit reset, rotation cancel).
     * Re-reads both detector snapshots and re-publishes the legacy merged view.
     */
    private fun triggerReMerge() {
        edgeSession = edgeDetector.session.value
        bottomSession = bottomDetector.session.value
        publishMerged()
    }

    /**同步版 — 保持老名字方便读. */
    private fun syncAfterEvent() = triggerReMerge()

    /** Pick whichever detector is "more active" and expose its session as the legacy view. */
    private fun publishMerged() {
        val e = edgeSession
        val b = bottomSession
        val chosen = when {
            e == null && b == null -> null
            e == null -> b
            b == null -> e
            // both active — edge wins (matches the old single-session behaviour)
            else -> if (isMoreActive(e, b)) e else b
        }
        _session.value = chosen?.toLegacy()
        _trackedType.value = chosen?.toLegacyType()
    }

    private fun isMoreActive(a: NewSession, b: NewSession): Boolean {
        return a.zone == NewZone.LEFT_EDGE || a.zone == NewZone.RIGHT_EDGE
    }

    private fun readNavigationModeFromSettings(): Int = try {
        Settings.Secure.getInt(context?.contentResolver, SETTINGS_NAVIGATION_MODE, MODE_OFF)
    } catch (_: Exception) {
        MODE_OFF
    }

    // --- new → legacy model translation ------------------------------------------

    private fun NewSession.toLegacy(): GestureSession = GestureSession(
        zone = zone.toLegacy(),
        startX = startX,
        startY = startY,
        startTimeMs = startTimeMs,
        state = state.toLegacy(),
        progress = progress,
    )

    private fun NewState.toLegacy(): GestureState = when (this) {
        NewState.GONE -> GestureState.GONE
        NewState.TRACKING -> GestureState.ENTRY
        NewState.ACTIVE -> GestureState.ACTIVE
        NewState.COMMITTED -> GestureState.COMMITTED
        NewState.INACTIVE -> GestureState.INACTIVE
        NewState.CANCELLED -> GestureState.CANCELLED
    }

    private fun NewType.toLegacy(): GestureType = when (this) {
        NewType.BACK -> GestureType.BACK
        NewType.HOME -> GestureType.HOME
        NewType.RECENTS -> GestureType.RECENTS
        NewType.SHADE -> GestureType.BACK // unreachable for now; SHADE has its own detector later
    }

    private fun NewZone.toLegacy(): TouchZone = when (this) {
        NewZone.LEFT_EDGE -> TouchZone.LEFT_EDGE
        NewZone.RIGHT_EDGE -> TouchZone.RIGHT_EDGE
        NewZone.BOTTOM -> TouchZone.BOTTOM
        NewZone.TOP -> TouchZone.NONE // legacy UI has no TOP concept
        NewZone.NONE -> TouchZone.NONE
    }

    private fun NewSession.toLegacyType(): GestureType? = when {
        state == NewState.TRACKING && (zone == NewZone.LEFT_EDGE || zone == NewZone.RIGHT_EDGE) -> GestureType.BACK
        state == NewState.COMMITTED -> when (zone) {
            NewZone.LEFT_EDGE, NewZone.RIGHT_EDGE -> GestureType.BACK
            NewZone.BOTTOM -> GestureType.HOME // resolved at commit; best-effort for affordance
            else -> null
        }
        else -> null
    }

    @Suppress("unused")
    @JvmField
    val bottomZoneEnabled: Boolean = true

    companion object {
        const val RESET_MODE_OFF = 0
        const val RESET_MODE_ON = 2
        const val LONG_PRESS_TIMEOUT_MS = 250L
        private const val SETTINGS_NAVIGATION_MODE = "navigation_mode"
        private const val MODE_OFF = 0
    }
}
