package com.antivocale.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GH #94: the pure geometry behind the capped-transcript position indicator
 * and reading-progress line. Every scroll-position fact the UI shows comes
 * from these two functions, so the clamps and the end states are pinned here.
 */
class TranscriptIndicatorGeometryTest {

    // ---- thumbGeometry ----

    @Test
    fun `nothing to scroll hides the thumb`() {
        assertNull(thumbGeometry(value = 0, maxValue = 0, viewportPx = 500, trackPx = 400, minThumbPx = 24))
    }

    @Test
    fun `zero dimensions hide the thumb`() {
        assertNull(thumbGeometry(10, 100, 0, 400, 24))
        assertNull(thumbGeometry(10, 100, 500, 0, 24))
    }

    @Test
    fun `thumb height is the viewport fraction of the content`() {
        // content = 1000 (viewport 300 + scrollable 700); track 400.
        val (top, h) = thumbGeometry(0, 700, 300, 400, 24)!!
        assertEquals(400f * 300f / 1000f, h, 0.01f)
        assertEquals(0f, top, 0.01f)
    }

    @Test
    fun `thumb at scroll end sits at the track bottom`() {
        val (top, h) = thumbGeometry(700, 700, 300, 400, 24)!!
        // End state: top + h == track height exactly.
        assertEquals(400f, top + h, 0.01f)
    }

    @Test
    fun `thumb never shrinks below the floor`() {
        // A tiny viewport in a huge content would collapse the thumb to ~4px.
        val (_, h) = thumbGeometry(0, 100_000, 100, 400, 24)!!
        assertEquals(24f, h, 0.01f)
    }

    @Test
    fun `thumb never exceeds the track when content fits`() {
        // content barely over one viewport: fraction ~0.8 of track.
        val (_, h) = thumbGeometry(0, 100, 900, 400, 24)!!
        assertEquals(400f * 900f / 1000f, h, 0.01f)
    }

    // ---- progressFraction ----

    @Test
    fun `progress hidden when there is nothing to scroll`() {
        assertEquals(true, progressFraction(0, 0, 500).isNaN())
        assertEquals(true, progressFraction(0, 100, 0).isNaN())
    }

    @Test
    fun `at the top the seen fraction is the viewport fraction`() {
        // viewport 300 of content 1000: seen = 0.3 even at scroll position 0.
        assertEquals(0.3f, progressFraction(0, 700, 300), 0.001f)
    }

    @Test
    fun `at the end the line is full`() {
        assertEquals(1f, progressFraction(700, 700, 300), 0.001f)
    }

    @Test
    fun `midway progress is monotonic between the bounds`() {
        val top = progressFraction(0, 700, 300)
        val mid = progressFraction(350, 700, 300)
        val end = progressFraction(700, 700, 300)
        assertEquals(true, top < mid && mid < end)
        assertEquals((350f + 300f) / 1000f, mid, 0.001f)
    }
}
