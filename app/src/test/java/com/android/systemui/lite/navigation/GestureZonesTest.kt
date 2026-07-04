package com.android.systemui.lite.navigation

import com.android.systemui.lite.model.TouchZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureZonesTest {

    private val displayWidth = 1080
    private val displayHeight = 2400
    private val edgeWidthPx = 48f
    // bottomZoneHeightPx = displayHeight * 0.06f = 144f
    private val bottomZoneHeightPx = 144f

    // ---- detectZone -------------------------------------------------------

    @Test
    fun `detectZone classifies left edge`() {
        assertEquals(TouchZone.LEFT_EDGE, GestureZones.detectZone(10f, 600f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone classifies right edge`() {
        assertEquals(TouchZone.RIGHT_EDGE, GestureZones.detectZone(1060f, 600f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone classifies bottom zone`() {
        // center column but very far down
        assertEquals(TouchZone.BOTTOM, GestureZones.detectZone(540f, displayHeight - 10f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone returns NONE for interior`() {
        assertEquals(TouchZone.NONE, GestureZones.detectZone(540f, 600f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone checks edge before bottom — bottom-left corner is LEFT_EDGE`() {
        // y is in the bottom band, but x is in the left edge strip → edge wins
        assertEquals(TouchZone.LEFT_EDGE, GestureZones.detectZone(10f, displayHeight - 10f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone checks edge before bottom — bottom-right corner is RIGHT_EDGE`() {
        assertEquals(TouchZone.RIGHT_EDGE, GestureZones.detectZone(1060f, displayHeight - 10f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone just past edge boundary is bottom, not edge`() {
        // x = edgeWidthPx + 1 sits outside the left edge strip but y is in the bottom band
        assertEquals(TouchZone.BOTTOM, GestureZones.detectZone(edgeWidthPx + 1, displayHeight - 10f, displayWidth, displayHeight, edgeWidthPx))
    }

    @Test
    fun `detectZone just above bottom band is NONE`() {
        // y just clear of the bottom zone
        assertEquals(TouchZone.NONE, GestureZones.detectZone(540f, displayHeight - bottomZoneHeightPx - 1, displayWidth, displayHeight, edgeWidthPx))
    }

    // ---- isExcluded -------------------------------------------------------

    @Test
    fun `isExcluded true in bottom-left corner of left edge`() {
        assertTrue(GestureZones.isExcluded(10f, displayHeight - 10f, displayWidth, displayHeight))
    }

    @Test
    fun `isExcluded true in bottom-right corner of right edge`() {
        assertTrue(GestureZones.isExcluded(displayWidth - 10f, displayHeight - 10f, displayWidth, displayHeight))
    }

    @Test
    fun `isExcluded false in upper-left — rotation zone is only bottom 20 percent`() {
        assertFalse(GestureZones.isExcluded(10f, 600f, displayWidth, displayHeight))
    }

    @Test
    fun `isExcluded false at the screen center`() {
        assertFalse(GestureZones.isExcluded(540f, 1200f, displayWidth, displayHeight))
    }

    @Test
    fun `isExcluded false in the horizontal bottom band away from the edges`() {
        // Bottom strip but horizontally centered — not in an edge column
        assertFalse(GestureZones.isExcluded(540f, displayHeight - 10f, displayWidth, displayHeight))
    }

    // ---- isEdgeGesture ----------------------------------------------------

    @Test
    fun `isEdgeGesture true for a strong rightward drag`() {
        assertTrue(GestureZones.isEdgeGesture(dx = 200f, dy = 0f))
    }

    @Test
    fun `isEdgeGesture false for a vertical drag even if magnitude is large`() {
        assertTrue(200f > 48f) // sanity — magnitude alone is not enough
        assertFalse(GestureZones.isEdgeGesture(dx = 0f, dy = 200f))
    }

    @Test
    fun `isEdgeGesture false when horizontal magnitude is below slop`() {
        assertFalse(GestureZones.isEdgeGesture(dx = 10f, dy = 0f))
    }

    @Test
    fun `isEdgeGesture true when horizontal dominates but vertical is nonzero`() {
        assertTrue(GestureZones.isEdgeGesture(dx = 200f, dy = 50f))
    }

    @Test
    fun `isEdgeGesture false when vertical dominates despite large x`() {
        assertFalse(GestureZones.isEdgeGesture(dx = 200f, dy = 400f))
    }
}
