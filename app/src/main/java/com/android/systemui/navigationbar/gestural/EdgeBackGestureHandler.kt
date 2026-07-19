package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Captures raw touch events from an [InputMonitor] and decides whether an
 * edge swipe should trigger "Back". Faithfully mirrors the decision state
 * machine of AOSP `EdgeBackGestureHandler` (Cactus), trimmed to the pieces
 * SystemUI-Lite needs:
 *
 *   DOWN   →  within edge + app in foreground + not in bottom gesture area
 *              ⇒ arm the gesture (mAllowGesture = true)
 *   MOVE   →  cancel on multitouch / longpress / net-vertical drag
 *              confirm on net-horizontal drag past the slop, then steal the
 *              gesture from the focused window (pilferPointers) and drive the
 *              visual panel
 *   UP     →  if net-drag crossed the activation threshold ⇒ inject BACK
 *              key event; otherwise cancel + animate the arrow away
 *
 * The handler never touches the screen itself — it owns only an
 * [InputMonitor] (a passive input channel) and relays MotionEvent copies to the
 * [EdgeBackPanelView] for rendering. Touch is "stolen" from the app only after
 * the horizontal threshold is crossed, so apps that begin reacting to the same
 * drag (ViewPager, drawers) get cancelled cleanly.
 */
class EdgeBackGestureHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EdgeBackGestureHandler"

        /** AOSP default: edge swipe older than this is treated as a long-press and cancelled. */
        private const val LONG_PRESS_TIMEOUT_MS = 250L

        /** Slop multiplier — intercept a touch earlier than the view touch slop. */
        private const val EDGE_SLOP_MULTIPLIER = 0.75f

        /** Drag must reach ~this fraction of the panel width to actually fire back. */
        private const val ACTIVATION_THRESHOLD_DP = 12f
    }

    /** Supplied true when a user app (not the launcher) is in the foreground. */
    var isAppInForeground: () -> Boolean = { true }

    /** Edge widths in px (left, right) and the bottom dead-zone in px. */
    var leftEdgeWidthPx: Int = 0
    var rightEdgeWidthPx: Int = 0
    var bottomGestureHeightPx: Int = 0

    /** Called with device-coordinate MotionEvents for the panel to render. */
    var onPanelMotionListener: ((MotionEvent) -> Unit)? = null
    /** Called once when a back gesture is confirmed on ACTION_UP. */
    var onBackTriggered: (() -> Unit)? = null
    /** Called when a gesture is cancelled before firing. */
    var onBackCancelled: (() -> Unit)? = null

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * EDGE_SLOP_MULTIPLIER

    private var inputMonitor: InputMonitor? = null
    private var inputReceiver: InputEventReceiver? = null

    private var isEnabled = false

    // Per-gesture state.
    private var mAllowGesture = false
    private var mOnLeftEdge = false
    private var mDownX = 0f
    private var mDownY = 0f
    private var mDownTime = 0L
    private var mThresholdCrossed = false
    private var mPilfered = false

    private val display: Display? get() = context.display

    // ---------------------------------------------------------------- lifecycle

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        isEnabled = enabled
        if (enabled) registerInputMonitor() else disposeInputMonitor()
    }

    private fun registerInputMonitor() {
        if (inputMonitor != null) return
        // "edge-swipe" is the AOSP channel name; the monitor receives a copy of
        // every touch before the focused window decides on it.
        inputMonitor = inputManager.monitorGestureInput("edge-swipe", display?.displayId ?: Display.DEFAULT_DISPLAY)
        val channel: InputChannel = inputMonitor!!.inputChannel
        inputReceiver = object : InputEventReceiver(channel, Looper.getMainLooper()) {
            override fun onInputEvent(event: InputEvent) {
                if (event is MotionEvent) {
                    val transformed = transformForDisplay(event)
                    onMotionEvent(transformed)
                    // transformForDisplay returns a fresh copy only when rotation != 0;
                    // we own that copy and must recycle it ourselves. When rotation == 0
                    // it returns the received event itself, which finishInputEvent releases.
                    if (transformed !== event) {
                        transformed.recycle()
                    }
                }
                // Release the received event back to the dispatcher. Do NOT recycle it
                // manually — finishInputEvent owns that lifecycle.
                finishInputEvent(event, false)
            }
        }
        Log.i(TAG, "input monitor registered (channel=${channel})")
    }

    private fun disposeInputMonitor() {
        inputReceiver?.dispose()
        inputReceiver = null
        inputMonitor?.dispose()
        inputMonitor = null
        Log.i(TAG, "input monitor disposed")
    }

    // --------------------------------------------------------- display rotation

    /**
     * Convert display-relative touch coords to the natural orientation.
     *
     * Returns the SAME event (unmodified) when rotation == 0, or a transformed COPY
     * when rotation != 0. The caller owns the returned copy and is responsible for
     * recycling it (see onInputEvent); the received event must NOT be recycled here
     * since finishInputEvent owns its lifecycle.
     */
    private fun transformForDisplay(event: MotionEvent): MotionEvent {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        if (rotation == Surface.ROTATION_0) return event
        val size = android.graphics.Point()
        display?.getRealSize(size)
        val rotated = MotionEvent.obtain(event)
        rotated.transform(MotionEvent.createRotateMatrix(rotation, size.x, size.y))
        return rotated
    }

    // --------------------------------------------------------- decision FSM

    private fun onMotionEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_MOVE -> if (mAllowGesture) handleMove(event)
            MotionEvent.ACTION_UP -> if (mAllowGesture) handleUp(event)
            MotionEvent.ACTION_CANCEL -> if (mAllowGesture) handleCancel(event)
        }
    }

    fun handleDown(event: MotionEvent) {
        val x = event.x
        val y = event.y
        val displayWidth = displayWidth()
        val displayHeight = displayHeight()

        mOnLeftEdge = x <= leftEdgeWidthPx
        val onRightEdge = x >= displayWidth - rightEdgeWidthPx
        val withinEdge = mOnLeftEdge || onRightEdge

        // Bottom dead-zone: leave room for the home handle / launcher gestures.
        val inBottomZone = y >= displayHeight - bottomGestureHeightPx
        val appFg = isAppInForeground()
        mAllowGesture = withinEdge && !inBottomZone && appFg

        Log.d(TAG, "DOWN x=$x y=$y dispW=$displayWidth leftW=$leftEdgeWidthPx"
                + " onLeft=$mOnLeftEdge onRight=$onRightEdge within=$withinEdge"
                + " bottomZone=$inBottomZone appFg=$appFg => armed=$mAllowGesture")

        if (mAllowGesture) {
            mDownX = x
            mDownY = y
            mDownTime = event.downTime
            mThresholdCrossed = false
            mPilfered = false
            onPanelMotionListener?.invoke(event)
        }
    }

    private fun handleMove(event: MotionEvent) {
        // Multitouch → cancel immediately.
        if (event.pointerCount > 1) {
            cancelGesture(event, "multitouch")
            return
        }

        // Long-press → cancel (user is holding, not swiping).
        if (event.eventTime - mDownTime > LONG_PRESS_TIMEOUT_MS) {
            cancelGesture(event, "longpress")
            return
        }

        val dx = Math.abs(event.x - mDownX)
        val dy = Math.abs(event.y - mDownY)

        if (dy > dx && dy > touchSlop) {
            // Net vertical drag — this is a scroll, not a back swipe.
            cancelGesture(event, "vertical")
            return
        }

        if (dx > dy && dx > touchSlop) {
            // Horizontal drag past the slop — claim the gesture from the app.
            if (!mThresholdCrossed) {
                mThresholdCrossed = true
                if (!mPilfered) {
                    mPilfered = true
                    inputMonitor?.pilferPointers()
                    Log.d(TAG, "pilferPointers — stealing drag from focused window")
                }
            }
        }

        // Forward every MOVE to the panel so the arrow tracks the finger.
        onPanelMotionListener?.invoke(event)
    }

    private fun handleUp(event: MotionEvent) {
        val dx = Math.abs(event.x - mDownX)
        val activated = mThresholdCrossed && dx > activationThresholdPx()
        Log.d(TAG, "UP: dx=$dx thresholdCrossed=$mThresholdCrossed activated=$activated")
        if (activated) {
            Log.i(TAG, "back triggered (dx=${dx}px)")
            onBackTriggered?.invoke()
        } else {
            onBackCancelled?.invoke()
        }
        onPanelMotionListener?.invoke(event)
        mAllowGesture = false
        mPilfered = false
    }

    private fun handleCancel(event: MotionEvent) {
        Log.d(TAG, "CANCEL received (left=$mOnLeftEdge) — gesture aborted by upstream")
        onBackCancelled?.invoke()
        onPanelMotionListener?.invoke(event)
        mAllowGesture = false
        mPilfered = false
    }

    private fun cancelGesture(event: MotionEvent, reason: String) {
        Log.d(TAG, "cancel: $reason")
        onBackCancelled?.invoke()
        // Forward a synthetic CANCEL so the panel resets its arrow.
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        onPanelMotionListener?.invoke(cancel)
        cancel.recycle()
        mAllowGesture = false
        mPilfered = false
    }

    // ------------------------------------------------------------- inject BACK

    /** Inject a real BACK key press into the focused window. */
    fun injectBackEvent() {
        val now = SystemClock.uptimeMillis()
        val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
            0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            android.view.InputDevice.SOURCE_KEYBOARD
        ).apply { this.displayId = displayId }
        val up = KeyEvent(
            now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
            0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            android.view.InputDevice.SOURCE_KEYBOARD
        ).apply { this.displayId = displayId }
        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    // --------------------------------------------------------------- geometry

    private fun displayWidth(): Int = display?.width ?: context.resources.displayMetrics.widthPixels
    private fun displayHeight(): Int = display?.height ?: context.resources.displayMetrics.heightPixels
    private fun activationThresholdPx(): Float =
        ACTIVATION_THRESHOLD_DP * context.resources.displayMetrics.density
}
