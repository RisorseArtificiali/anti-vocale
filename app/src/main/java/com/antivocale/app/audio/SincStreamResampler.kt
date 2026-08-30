package com.antivocale.app.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming variant of AudioPreprocessor's Kaiser-windowed sinc resampler
 * (TASK-416). Same coefficients, same arithmetic order, but fed chunk by chunk:
 * the whole-buffer path held the input-rate signal twice in the 256MB Dalvik
 * heap (input-rate chunks + merged copy, ~230MB for a 10-minute 48kHz file,
 * the Crashlytics OOM class of 2026-08-29), while this variant retains only a
 * bounded tail (chunk + window) and emits 16kHz output as decode chunks
 * arrive: peak heap ~2x the FINAL size, independent of the source rate.
 *
 * Equivalence contract (pinned bit-exact by SincStreamResamplerTest):
 * process(c1)..process(cn) + flush, concatenated, equals
 * AudioPreprocessor.resampleFloat(c1 ++ .. ++ cn, ratio) exactly, including
 * edge behavior (out-of-range window taps contribute zero).
 *
 * Retained-state contract (the heap guarantee): at any point the internal
 * buffer holds at most the unconsumed window plus the current chunk;
 * [retainedInputSize] is exposed for tests to pin that bound.
 *
 * NOT thread-safe; one instance per stream.
 */
internal class SincStreamResampler(private val ratio: Double) {

    private val table = SincResamplerTable.build(ratio)
    private val numTaps = SincResamplerTable.NUM_TAPS
    private val halfTaps = numTaps / 2

    /** Unconsumed input tail, starting at global index [pendingStart]. */
    private var pending = FloatArray(0)
    private var pendingStart = 0
    private var totalIn = 0
    private var nextOut = 0

    /**
     * Consumes one input chunk and returns every output sample whose full
     * interpolation window is already covered (possibly an empty array).
     */
    fun process(chunk: FloatArray): FloatArray {
        appendToPending(chunk)
        val out = emitCovered()
        dropConsumed()
        return out
    }

    /**
     * Emits the tail: outputs whose window extends past the input end. Out-of-range
     * taps contribute zero, matching the whole-buffer resampler's edge behavior.
     * Call once, after the last chunk.
     */
    fun flush(): FloatArray {
        val total = (totalIn / ratio).toInt()
        val out = FloatArray(total - nextOut)
        for (i in nextOut until total) {
            out[i - nextOut] = emit(i)
        }
        nextOut = total
        return out
    }

    /** Test seam for the heap invariant: input samples currently retained. */
    internal fun retainedInputSize(): Int = pending.size

    private fun appendToPending(chunk: FloatArray) {
        val merged = FloatArray(pending.size + chunk.size)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        System.arraycopy(chunk, 0, merged, pending.size, chunk.size)
        pending = merged
        totalIn += chunk.size
    }

    /** Emits every not-yet-emitted output whose window end is inside the input. */
    private fun emitCovered(): FloatArray {
        // Same total as flush(): the whole-buffer resampler emits exactly
        // floor(totalIn / ratio) outputs, and for ratio > halfTaps (sources above
        // 128kHz) output `total`'s window can still end inside the input. Without
        // this cap, process() would emit it and flush() would then allocate a
        // negative-length tail (review finding on the first cut, proven at ratio
        // 11.025 and 12.0 with specific remainders).
        val total = (totalIn / ratio).toInt()
        var count = 0
        while (nextOut + count < total && windowEnd(nextOut + count) < totalIn) count++
        val out = FloatArray(count)
        for (j in 0 until count) {
            out[j] = emit(nextOut)
            nextOut++
        }
        return out
    }

    /** Drops the prefix no future output can reference (before the next window start). */
    private fun dropConsumed() {
        val nextWindowStart = max(0, (nextOut * ratio).toInt() - halfTaps)
        if (nextWindowStart > pendingStart) {
            pending = pending.copyOfRange(nextWindowStart - pendingStart, pending.size)
            pendingStart = nextWindowStart
        }
    }

    /** Last input index touched by output [i]'s window (center + halfTaps). */
    private fun windowEnd(i: Int) = (i * ratio).toInt() + halfTaps

    /** Computes output [i] from [pending]; taps outside [0, totalIn) contribute zero. */
    private fun emit(i: Int): Float {
        val srcPos = i * ratio
        val center = srcPos.toInt()
        val frac = srcPos - center
        val phase = (frac * SincResamplerTable.PHASES).toInt().coerceIn(0, SincResamplerTable.PHASES - 1)
        val coeffs = phase * numTaps

        var sum = 0.0
        for (k in 0 until numTaps) {
            val idx = center + k - halfTaps
            if (idx >= 0 && idx < totalIn) {
                sum += pending[idx - pendingStart] * table[coeffs + k]
            }
        }
        return sum.toFloat()
    }
}

/**
 * Polyphase table shared by the whole-buffer (AudioPreprocessor.resampleFloat) and
 * streaming resamplers: the single definition of the Kaiser-windowed sinc coefficients.
 */
internal object SincResamplerTable {
    const val NUM_TAPS = 16
    const val PHASES = 64
    private const val KAISER_BETA = 5.0

    private val cache = ConcurrentHashMap<Double, DoubleArray>()

    /**
     * Builds the PHASES × NUM_TAPS coefficient table for [ratio], once per ratio
     * (each entry ~8KB). The returned array is shared and must not be mutated.
     */
    fun build(ratio: Double): DoubleArray = cache.computeIfAbsent(ratio) { buildTable(it) }

    private fun buildTable(ratio: Double): DoubleArray {
        val cutoff = if (ratio > 1.0) 1.0 / ratio else 1.0
        val besselDenom = besselI0(KAISER_BETA)

        val table = DoubleArray(PHASES * NUM_TAPS)
        for (p in 0 until PHASES) {
            val frac = p.toDouble() / PHASES
            for (k in 0 until NUM_TAPS) {
                val x = (k - NUM_TAPS / 2).toDouble() - frac
                val sincVal = if (abs(x) < 1e-10) {
                    cutoff
                } else {
                    cutoff * sin(PI * cutoff * x) / (PI * cutoff * x)
                }
                val windowPos = x / (NUM_TAPS / 2)
                val kaiserArg = KAISER_BETA * sqrt(max(0.0, 1.0 - windowPos * windowPos))
                table[p * NUM_TAPS + k] = sincVal * besselI0(kaiserArg) / besselDenom
            }
        }
        return table
    }

    /** Modified Bessel function I₀(x) via Taylor series; AudioPreprocessor's reflection-pinned method delegates here. */
    fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        for (k in 1 until 25) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k))
            sum += term
            if (term < 1e-12) break
        }
        return sum
    }
}
