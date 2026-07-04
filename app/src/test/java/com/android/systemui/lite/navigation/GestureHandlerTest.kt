package com.android.systemui.lite.navigation

import com.android.systemui.lite.model.GestureState
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.TouchZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [GestureHandler]. All timing uses [TestScope.advanceTimeBy] via [runTest] so
 * the long-press guard and post-commit reset delay are verified deterministically without
 * sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureHandlerTest {

    private val displayWidth = 1080
    private val displayHeight = 2400
    private val edgeX = 22f            // inside the left edge strip (x < 48)
    private val bottomY = displayHeight - 30f  // inside bottom zone (y > 2400 - 0.06*2400)
    private val midX = displayWidth / 2f

    private fun newHandler(
        scope: TestScope,
        gestureMode: Boolean = true,
        onAction: (GestureType) -> Unit = {}
    ): Pair<GestureHandler, MutableList<GestureType>> {
        val actions = mutableListOf<GestureType>()
        val h = GestureHandler(
            onActionInit = { onAction(it); actions.add(it) },
            context = null,
            navigationModeProvider = {
                if (gestureMode) GestureHandler.RESET_MODE_ON else GestureHandler.RESET_MODE_OFF
            },
            scope = scope,
            density = 1f,
            commitThresholdPx = 64f,
            homeSwipeDp = 80f,
            recentsSwipeDp = 150f,
            resetDelayMs = 60L
        )
        return h to actions
    }

    // ---- Initial state --------------------------------------------------------

    @Test
    fun `session is null initially - GONE`() = runTest {
        val (h, _) = newHandler(this)
        assertNull(h.session.value)
    }

    @Test
    fun `trackedType is null initially`() = runTest {
        val (h, _) = newHandler(this)
        assertNull(h.trackedType.value)
    }

    // ---- onDown ---------------------------------------------------------------

    @Test
    fun `onDown on left edge starts an ENTRY session with BACK tracked`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        val s = h.session.value
        assertNotNull(s)
        assertEquals(TouchZone.LEFT_EDGE, s!!.zone)
        assertEquals(GestureState.ENTRY, s.state)
        assertEquals(0f, s.progress)
        assertEquals(GestureType.BACK, h.trackedType.value)
    }

    @Test
    fun `onDown on right edge starts an ENTRY session with BACK tracked`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(displayWidth - edgeX, 600f, displayWidth, displayHeight)
        val s = h.session.value
        assertNotNull(s)
        assertEquals(TouchZone.RIGHT_EDGE, s!!.zone)
        assertEquals(GestureType.BACK, h.trackedType.value)
    }

    @Test
    fun `onDown on bottom zone starts ENTRY session with no tracked type`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(midX, bottomY, displayWidth, displayHeight)
        val s = h.session.value
        assertNotNull(s)
        assertEquals(TouchZone.BOTTOM, s!!.zone)
        assertEquals(GestureState.ENTRY, s.state)
        assertNull(h.trackedType.value)
    }

    @Test
    fun `onDown on interior does nothing`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(midX, 600f, displayWidth, displayHeight)
        assertNull(h.session.value)
    }

    @Test
    fun `onDown on exclusion corner is ignored`() = runTest {
        val (h, _) = newHandler(this)
        // Bottom-left corner falls in rotation-quickswitch exclusion -> session must not start.
        h.onDown(edgeX, displayHeight - 30f, displayWidth, displayHeight)
        assertNull(h.session.value)
    }

    @Test
    fun `onDown rejected when gesture nav mode is off`() = runTest {
        val (h, _) = newHandler(this, gestureMode = false)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        assertNull(h.session.value)
    }

    // ---- onMove: ENTRY <-> ACTIVE <-> INACTIVE --------------------------------

    @Test
    fun `onMove past commit threshold promotes ENTRY to ACTIVE and computes progress`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 200f, dy = 0f, totalX = 200f, totalY = 0f)
        val s = h.session.value
        assertEquals(GestureState.ACTIVE, s!!.state)
        // progress = abs(200) / (displayWidth / 2) ~= 0.370
        assertTrue("progress=${s.progress}", s.progress > 0.3f && s.progress < 0.4f)
    }

    @Test
    fun `onMove below threshold stays ENTRY`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 10f, dy = 0f, totalX = 10f, totalY = 0f)
        assertEquals(GestureState.ENTRY, h.session.value!!.state)
    }

    @Test
    fun `onMove reverse past start flips ACTIVE back to INACTIVE`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 200f, dy = 0f, totalX = 200f, totalY = 0f)
        assertEquals(GestureState.ACTIVE, h.session.value!!.state)
        // 200 + (-200-epsilon) -> totalDragX becomes negative -> back past start for LEFT_EDGE.
        h.onMove(dx = -300f, dy = 0f, totalX = -100f, totalY = 0f)
        assertEquals(GestureState.INACTIVE, h.session.value!!.state)
    }

    @Test
    fun `onMove that is purely vertical does not promote ENTRY to ACTIVE`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 0f, dy = 200f, totalX = 0f, totalY = 200f)
        assertEquals(GestureState.ENTRY, h.session.value!!.state)
    }

    // ---- onUp: edge commit ----------------------------------------------------

    @Test
    fun `onUp on ACTIVE edge session past threshold commits BACK`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 200f, dy = 0f, totalX = 200f, totalY = 0f)
        h.onUp()
        assertEquals(GestureState.COMMITTED, h.session.value!!.state)
        assertEquals(1f, h.session.value!!.progress)
        assertEquals(listOf(GestureType.BACK), actions)
    }

    @Test
    fun `onUp on INACTIVE edge session cancels without emitting BACK`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 200f, dy = 0f, totalX = 200f, totalY = 0f)
        h.onMove(dx = -300f, dy = 0f, totalX = -100f, totalY = 0f)
        h.onUp()
        assertEquals(GestureState.CANCELLED, h.session.value!!.state)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `onUp on ENTRY edge session that never crossed threshold cancels`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onUp()
        assertEquals(GestureState.CANCELLED, h.session.value!!.state)
        assertTrue(actions.isEmpty())
    }

    // ---- onUp: bottom gestures -----------------------------------------------

    @Test
    fun `onUp on bottom zone with large upward drag emits RECENTS`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(midX, bottomY, displayWidth, displayHeight)
        h.onMove(dx = 0f, dy = -200f, totalX = 0f, totalY = -200f)
        h.onUp()
        assertEquals(GestureState.COMMITTED, h.session.value!!.state)
        assertEquals(listOf(GestureType.RECENTS), actions)
    }

    @Test
    fun `onUp on bottom zone with moderate upward drag emits HOME`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(midX, bottomY, displayWidth, displayHeight)
        h.onMove(dx = 0f, dy = -100f, totalX = 0f, totalY = -100f)
        h.onUp()
        assertEquals(GestureState.COMMITTED, h.session.value!!.state)
        assertEquals(listOf(GestureType.HOME), actions)
    }

    @Test
    fun `onUp on bottom zone with tiny upward drag cancels`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(midX, bottomY, displayWidth, displayHeight)
        h.onMove(dx = 0f, dy = -10f, totalX = 0f, totalY = -10f)
        h.onUp()
        assertEquals(GestureState.CANCELLED, h.session.value!!.state)
        assertTrue(actions.isEmpty())
    }

    // ---- Long-press guard -----------------------------------------------------

    @Test
    fun `long-press guard cancels an untouched session after timeout`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        assertEquals(GestureState.ENTRY, h.session.value!!.state)
        // Virtual clock advances; the guard coroutine fires at longPressTimeoutMs.
        testScheduler.advanceTimeBy(GestureHandler.LONG_PRESS_TIMEOUT_MS + 10)
        testScheduler.runCurrent()
        assertEquals(GestureState.CANCELLED, h.session.value!!.state)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `long-press guard is cancelled when user moves`() = runTest {
        val (h, _) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 50f, dy = 0f, totalX = 50f, totalY = 0f)
        testScheduler.advanceTimeBy(GestureHandler.LONG_PRESS_TIMEOUT_MS + 10)
        testScheduler.runCurrent()
        // The guard was cancelled on first move; session is still alive (ACTIVE).
        assertFalse(h.session.value!!.state == GestureState.CANCELLED)
    }

    // ---- Multi-touch cancellation ---------------------------------------------

    @Test
    fun `cancelOnMultiTouch flips a session to CANCELLED without emitting BACK`() = runTest {
        val (h, actions) = newHandler(this)
        h.onDown(edgeX, 600f, displayWidth, displayHeight)
        h.onMove(dx = 200f, dy = 0f, totalX = 200f, totalY = 0f)
        assertEquals(GestureState.ACTIVE, h.session.value!!.state)
        h.cancelOnMultiTouch()
        assertEquals(GestureState.CANCELLED, h.session.value!!.state)
        assertTrue(actions.isEmpty())
    }

    // ---- refreshNavigationMode ------------------------------------------------

    @Test
    fun `refreshNavigationMode updates in-memory mode`() = runTest {
        var mode = GestureHandler.RESET_MODE_ON
        val h = GestureHandler(
            onActionInit = {},
            navigationModeProvider = { mode }
        )
        assertEquals(GestureHandler.RESET_MODE_ON, h.navigationMode)
        mode = GestureHandler.RESET_MODE_OFF
        h.refreshNavigationMode()
        assertEquals(GestureHandler.RESET_MODE_OFF, h.navigationMode)
    }
}
