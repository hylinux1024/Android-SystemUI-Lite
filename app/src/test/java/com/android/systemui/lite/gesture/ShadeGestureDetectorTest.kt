package com.android.systemui.lite.gesture

import com.android.systemui.lite.gesture.detector.ShadeGestureDetector
import com.android.systemui.lite.gesture.model.GestureState
import com.android.systemui.lite.gesture.model.GestureType
import com.android.systemui.lite.gesture.sink.GestureActionSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ShadeGestureDetector]. Exercises the three decode paths: tap, small-drag
 * cancel, and fling-to-open. All timing uses [TestScope] virtual time so fling-velocity math
 * is deterministic without a real dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShadeGestureDetectorTest {

    private val displayWidth = 1080
    private val displayHeight = 2400
    private val topY = 20f // inside the top band (< 60f default)

    private data class Recorded(
        val committed: MutableList<GestureType> = mutableListOf(),
        val cancelled: MutableList<GestureType> = mutableListOf(),
        val progress: MutableList<Float> = mutableListOf(),
    )

    private fun newDetector(
        scope: TestScope,
        shadeRangePx: Float = 2400f,
    ): Pair<ShadeGestureDetector, Recorded> {
        val rec = Recorded()
        val sink = object : GestureActionSink {
            override fun onCommitted(type: GestureType) { rec.committed.add(type) }
            override fun onCancelled(type: GestureType) { rec.cancelled.add(type) }
            override fun onProgress(type: GestureType, progress: Float) { rec.progress.add(progress) }
        }
        val d = ShadeGestureDetector(
            scope = scope,
            shadeRangePx = shadeRangePx,
            sink = sink,
        ).apply { updateDisplaySize(displayWidth, displayHeight) }
        return d to rec
    }

    // ---- onDown ---------------------------------------------------------------

    @Test
    fun `onDown in top band starts a tracking session`() = runTest {
        val (d, _) = newDetector(this)
        d.onDown(540f, topY, displayWidth, displayHeight)
        val s = d.session.value
        assertNotNull(s)
        assertEquals(GestureState.TRACKING, s!!.state)
    }

    // ---- onMove ---------------------------------------------------------------

    @Test
    fun `onMove with positive drag emits progress proportional to drag distance`() = runTest {
        val (d, rec) = newDetector(this)
        d.onDown(540f, topY, displayWidth, displayHeight)
        d.onMove(0f, 120f, 0f, 120f)
        d.onMove(0f, 120f, 0f, 240f)
        // Last progress 240/2400 = 0.1
        assertTrue("progress=${rec.progress}", rec.progress.isNotEmpty())
        assertEquals(0.1f, rec.progress.last(), 0.02f)
    }

    // ---- onUp: tap ------------------------------------------------------------

    @Test
    fun `onUp with sub-slop drag commits SHADE (toggle path)`() = runTest {
        val (d, rec) = newDetector(this)
        d.onDown(540f, topY, displayWidth, displayHeight)
        d.onMove(0f, 2f, 0f, 2f)
        d.onUp()
        assertEquals(listOf(GestureType.SHADE), rec.committed)
        assertTrue(rec.cancelled.isEmpty())
    }

    // ---- onUp: small non-fling drag -------------------------------------------

    @Test
    fun `onUp with sub-threshold non-fling drag cancels`() = runTest {
        val (d, rec) = newDetector(this)
        d.onDown(540f, topY, displayWidth, displayHeight)
        // Move > slop (8px) so not a tap, but < flingDistancePx (40px) so the fling branch
        // cannot fire, and well below 1/3 shade range (≈ 800px) so shouldOpen is false.
        d.onMove(0f, 20f, 0f, 20f)
        d.onUp()
        assertTrue(rec.committed.isEmpty())
        // The commit-link expects a SHADE cancel — detector logs the type it built up.
        assertTrue(rec.cancelled.any { it == GestureType.SHADE })
    }

    // ---- onUp: fling-to-open --------------------------------------------------

    @Test
    fun `onUp with strong fling-to-open commits SHADE`() = runTest {
        val (d, rec) = newDetector(this, shadeRangePx = 1000f)
        d.onDown(540f, topY, displayWidth, displayHeight)
        // Drag past flingDistance (40), with velocity well above 800px/s. We immediately
        // deliver the full drag in one frame; time-based velocity will be high because
        // elapsed ms is tiny.
        d.onMove(0f, 500f, 0f, 500f)
        d.onUp()
        assertEquals(listOf(GestureType.SHADE), rec.committed)
    }

    // ---- cancel ---------------------------------------------------------------

    @Test
    fun `cancel flips a tracking session to CANCELLED and notifies sink`() = runTest {
        val (d, rec) = newDetector(this)
        d.onDown(540f, topY, displayWidth, displayHeight)
        d.onMove(0f, 100f, 0f, 100f)
        d.cancel()
        val s = d.session.value
        // After cancel the detector reset()s to null within the same call.
        assertNull(s)
        assertTrue(rec.cancelled.any { it == GestureType.SHADE })
        assertTrue(rec.committed.isEmpty())
    }
}
