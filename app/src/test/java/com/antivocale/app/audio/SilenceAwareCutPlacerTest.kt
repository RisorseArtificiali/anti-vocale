package com.antivocale.app.audio

import com.antivocale.app.util.concatFloatArrays
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

/**
 * TASK-410 prototype A: silence-aware cut placement, pure-function tests on
 * synthetic audio (tone bursts with known pauses; no device, no model).
 */
class SilenceAwareCutPlacerTest {

    private val sr = 16000

    /** A tone burst of [seconds] at full-ish amplitude. */
    private fun tone(seconds: Double, amp: Double = 0.5): FloatArray =
        FloatArray((seconds * sr).toInt()) { (amp * sin(2.0 * Math.PI * 440.0 * it / sr)).toFloat() }

    /** Digital silence of [seconds]. */
    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * sr).toInt())


    @Test
    fun `audio under the cap is a single chunk with no cuts`() {
        val samples = tone(10.0)
        assertEquals(0, SilenceAwareCutPlacer.cutOffsets(samples, sr, 30).size)
    }

    @Test
    fun `cut moves to the pause nearest the ideal boundary`() {
        // 28s speech, 2s pause (28.0-30.0), then 10s speech: the ideal cut
        // at 30s falls INSIDE the pause; the nearest silent frame to the
        // ideal is the pause's END, so the pause rides chunk 0's tail and
        // the next word starts chunk 1 clean.
        val samples = concatFloatArrays(tone(28.0), silence(2.0), tone(10.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(1, cuts.size)
        assertEquals(30.0, cuts[0].toDouble() / sr, 0.05)
    }

    @Test
    fun `cut moves backward to a pause before the boundary`() {
        // 28.5s speech, 1s pause (28.5-29.5), then speech: ideal 30s sits
        // in speech just after the pause; the nearest silence is the pause
        // END at 29.5, one frame short of the ideal.
        val samples = concatFloatArrays(tone(28.5), silence(1.0), tone(13.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(1, cuts.size)
        assertEquals(29.5, cuts[0].toDouble() / sr, 0.05)
    }

    @Test
    fun `a pause outside the search window never moves the cut`() {
        // 25s speech, 2s pause (25-27), 13s speech: the pause ends 3s
        // before the ideal 30s cut, beyond the 2s window, so the cut
        // stays fixed (silence detection must not reach arbitrarily far).
        val samples = concatFloatArrays(tone(25.0), silence(2.0), tone(13.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(1, cuts.size)
        assertEquals(30.0, cuts[0].toDouble() / sr, 0.001)
    }

    @Test
    fun `cuts never fall below the minimum chunk span`() {
        // A long pause straddling the second boundary must not drag the
        // second cut back under MIN_CHUNK_SECONDS from the first.
        val samples = concatFloatArrays(
            tone(30.5), silence(2.0), tone(28.0), silence(4.0), tone(28.0), silence(2.0), tone(10.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(3, cuts.size)
        var prev = 0L
        for (c in cuts) {
            val spanSeconds = (c - prev).toDouble() / sr
            assert(spanSeconds >= SilenceAwareCutPlacer.MIN_CHUNK_SECONDS) {
                "chunk span from $prev to $c is $spanSeconds, under the floor"
            }
            prev = c
        }
        // and every cut sits within the backward window of its RE-ANCHORED
        // ideal (ideal i+1 = cut i + cap: one backward move shifts all later
        // ideals, so the fixed grid is not the invariant)
        var anchor = 0L
        cuts.forEach { c ->
            val ideal = anchor + 30L * sr
            val slack = SilenceAwareCutPlacer.SEARCH_WINDOW_SECONDS.toLong() * sr +
                SilenceAwareCutPlacer.FRAME_MS * sr / 1000
            assert(ideal - slack <= c && c <= ideal) {
                "cut $c drifted outside [$ideal-$slack, $ideal]"
            }
            anchor = c
        }
    }

    @Test
    fun `the trailing remainder is never emitted as a cut`() {
        // 61s of speech: cuts at 30 and 60; 61 itself is the array end.
        val samples = tone(61.0)
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(listOf(30L * sr, 60L * sr), cuts.toList())
        assert(cuts.all { it < samples.size.toLong() })
    }

    @Test
    fun `degenerate all-silent track falls back to fixed cuts`() {
        // p90 = 0 -> floor 0 -> detection disabled by contract.
        val samples = silence(95.0)
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(listOf(30L * sr, 60L * sr, 90L * sr), cuts.toList())
    }

    @Test
    fun `placeCut returns the cap itself when the region is at most the cap`() {
        // The streaming primitive's short-region contract: never a cut past
        // the region end.
        val region = tone(30.0)
        assertEquals(30L * sr, SilenceAwareCutPlacer.placeCut(listOf(region), sr, 30 * sr, region.size).toLong())
        // a cap PAST the region end clamps to the region end
        assertEquals(region.size.toLong(), SilenceAwareCutPlacer.placeCut(listOf(region), sr, 31 * sr, region.size).toLong())
    }

    @Test
    fun `placeCut moves a streaming region cut onto the pause`() {
        // 28s speech + 2s pause + 4s speech under a 30s cap: the region
        // handed over once the lookahead fills is 34s; the cut must land
        // inside the pause, never mid-word at the exact 30s mark.
        val region = concatFloatArrays(tone(28.0), silence(2.0), tone(4.0))
        val cut = SilenceAwareCutPlacer.placeCut(listOf(region), sr, 30 * sr, region.size)
        assert(cut in 28L * sr..30L * sr) { "cut at ${cut.toDouble() / sr}s is outside the pause" }
    }

    @Test
    fun `placeCut falls back to the exact cap on continuous speech`() {
        val region = tone(34.0)
        assertEquals(30L * sr, SilenceAwareCutPlacer.placeCut(listOf(region), sr, 30 * sr, region.size).toLong())
    }
}
