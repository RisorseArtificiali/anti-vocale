package com.antivocale.app.audio

import com.antivocale.app.util.concatFloatArrays
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

/**
 * TASK-410 integration seam: the batch chunker consumes the placer's cut
 * offsets correctly (GH #92's chunkRangesMs alignment contract: range i is
 * chunk i's span by construction, wherever the cut landed).
 */
class SilenceAwareChunkingTest {

    private val sr = 16000

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * sr).toInt())

    private fun tone(seconds: Double): FloatArray =
        FloatArray((seconds * sr).toInt()) { (0.5 * sin(2.0 * Math.PI * 440.0 * it / sr)).toFloat() }

    @Test
    fun `placed cuts slice at the given boundaries and ranges follow`() {
        // Pauses at 28-30s and 57-59s. Cut 1: the nearest silent frame to
        // the ideal 30 is the pause end, 30.0. Cut 2's ideal re-anchors at
        // cut1+30 = 60, window [58,60]; the pause ends at 59, so the cut
        // lands at its last silent frame, 59.0.
        val samples = concatFloatArrays(tone(28.0), silence(2.0), tone(27.0), silence(2.0), tone(11.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        assertEquals(30.0, cuts[0].toDouble() / sr, 0.05)
        assertEquals(59.0, cuts[1].toDouble() / sr, 0.05)

        val result = AudioPreprocessor().chunkFloatAudio(
            samples, sr, samples.size.toDouble() / sr, 30, 0L, cuts)

        assertEquals(3, result.chunkCount)
        // GH #92: each range is its chunk's span, in original coordinates
        val c0 = cuts[0].toInt() * 1000L / sr
        val c1 = cuts[1].toInt() * 1000L / sr
        assertEquals(listOf(0L to c0, c0 to c1, c1 to 70_000L), result.chunkRangesMs)
    }

    @Test
    fun `every placed chunk stays at or under the cap`() {
        // The model-ceiling invariant the backward-only window protects
        // (whisper discards past 29.5s; placement must never make it worse).
        val samples = concatFloatArrays(tone(20.0), tone(2.0), tone(20.0), tone(2.0), tone(20.0), tone(2.0), tone(20.0))
        val cuts = SilenceAwareCutPlacer.cutOffsets(samples, sr, 30)
        val result = AudioPreprocessor().chunkFloatAudio(
            samples, sr, samples.size.toDouble() / sr, 30, 0L, cuts)
        result.chunks.forEach {
            assert(it.size <= 30 * sr) { "chunk of ${it.size.toDouble() / sr}s exceeds the cap" }
        }
        // all audio kept: concatenation of chunks reproduces the input
        assertEquals(samples.size, result.chunks.sumOf { it.size })
    }
}
