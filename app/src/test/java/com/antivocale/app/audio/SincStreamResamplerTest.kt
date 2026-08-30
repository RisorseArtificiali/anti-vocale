package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * TASK-416: the streaming variant of the Kaiser sinc resampler must be
 * INDISTINGUISHABLE from the whole-buffer one (the whole-buffer path held the
 * input-rate signal twice in the 256MB heap; the streaming one is the fix).
 *
 * Equivalence is asserted BIT-EXACT (same coefficient table, same summation
 * order), and the allocation guard checks that the streaming path never
 * materializes the input-rate signal whole.
 */
@RunWith(JUnit4::class)
class SincStreamResamplerTest {

    private fun resampleWhole(input: FloatArray, ratio: Double): FloatArray {
        val method = AudioPreprocessor::class.java.getDeclaredMethod(
            "resampleFloat", FloatArray::class.java, Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(AudioPreprocessor(), input, ratio) as FloatArray
    }

    private fun resampleStreaming(input: FloatArray, ratio: Double, chunkSizes: Iterator<Int>): FloatArray {
        val r = SincStreamResampler(ratio)
        val out = mutableListOf<FloatArray>()
        var i = 0
        while (i < input.size) {
            val n = chunkSizes.next().coerceIn(1, input.size - i)
            out += r.process(input.copyOfRange(i, i + n))
            i += n
        }
        out += r.flush()
        val merged = FloatArray(out.sumOf { it.size })
        var off = 0
        for (c in out) { System.arraycopy(c, 0, merged, off, c.size); off += c.size }
        return merged
    }

    private fun randomSignal(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        return FloatArray(n) { (sin(2 * PI * (0.05 + rnd.nextDouble() * 0.4) * it) * 0.8).toFloat() }
    }

    // ========== Equivalence vs the whole-buffer resampler ==========

    @Test
    fun `streaming equals whole-buffer for 48k to 16k across chunk sizes`() {
        val input = randomSignal(50_000, seed = 7)
        val ratio = 48000.0 / 16000.0
        val expected = resampleWhole(input, ratio)
        for (chunkSize in listOf(1, 2, 7, 64, 1024, 50_000)) {
            val got = resampleStreaming(input, ratio, generateSequence { chunkSize }.iterator())
            assertEquals("size (chunk=$chunkSize)", expected.size.toLong(), got.size.toLong())
            assertTrue("samples differ (chunk=$chunkSize)", expected.contentEquals(got))
        }
    }

    @Test
    fun `streaming equals whole-buffer for 44100 to 16000 (non-integer ratio)`() {
        val input = randomSignal(30_000, seed = 11)
        val ratio = 44100.0 / 16000.0
        val expected = resampleWhole(input, ratio)
        var k = 3
        val got = resampleStreaming(input, ratio, generateSequence { k = (k * 7) % 101; k + 1 }.iterator())
        assertEquals(expected.size.toLong(), got.size.toLong())
        assertTrue(expected.contentEquals(got))
    }

    @Test
    fun `streaming equals whole-buffer for an upsampling ratio below one`() {
        val input = randomSignal(5_000, seed = 13)
        val ratio = 8000.0 / 16000.0
        val expected = resampleWhole(input, ratio)
        val got = resampleStreaming(input, ratio, generateSequence { 33 }.iterator())
        assertEquals(expected.size.toLong(), got.size.toLong())
        assertTrue(expected.contentEquals(got))
    }

    @Test
    fun `output length is floor of total over ratio`() {
        val r = SincStreamResampler(3.0)
        val emitted = r.process(FloatArray(10_000)).size
        assertEquals(3_333L, (emitted + r.flush().size).toLong())
    }

    @Test
    fun `empty input produces nothing`() {
        val r = SincStreamResampler(3.0)
        assertEquals(0, r.process(FloatArray(0)).size)
        assertEquals(0, r.flush().size)
    }

    @Test
    fun `process emits nothing until the window is fully covered`() {
        val r = SincStreamResampler(3.0)
        assertEquals(0, r.process(FloatArray(8)).size)      // window needs 16 samples
        assertTrue(r.process(FloatArray(8)).isNotEmpty())  // now covered
    }

    // ========== Heap invariant (the reason this class exists) ==========

    @Test
    fun `retained input stays bounded by chunk plus window regardless of stream length`() {
        // 4M samples at 48kHz = ~83s of audio: the whole-buffer path would hold
        // the input twice (~32MB); the streaming contract is that the resampler
        // never retains more than one chunk plus the interpolation window.
        val r = SincStreamResampler(48000.0 / 16000.0)
        val chunk = FloatArray(4096)
        var maxRetained = 0
        var fed = 0
        while (fed < 4_000_000) {
            r.process(chunk)
            maxRetained = maxOf(maxRetained, r.retainedInputSize())
            fed += chunk.size
        }
        r.flush()
        assertTrue(
            "retained input peaked at $maxRetained samples; bound is chunk (4096) + taps (16) + slack",
            maxRetained <= 4096 + 16 + 8,
        )
    }

    @Test
    fun `retained input stays bounded for tiny chunks and upsampling too`() {
        val r = SincStreamResampler(8000.0 / 16000.0) // upsampling: 2 outputs per input
        val chunk = FloatArray(7)
        var maxRetained = 0
        repeat(20_000) {
            r.process(chunk)
            maxRetained = maxOf(maxRetained, r.retainedInputSize())
        }
        r.flush()
        assertTrue("retained input peaked at $maxRetained; bound is chunk (7) + taps (16) + slack", maxRetained <= 7 + 16 + 8)
    }
}
