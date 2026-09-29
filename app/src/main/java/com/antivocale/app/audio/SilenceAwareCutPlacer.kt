package com.antivocale.app.audio

/**
 * TASK-410 prototype A: silence-aware cut placement for the fixed-window
 * chunking paths. Parakeet/Whisper/GigaAM cut long audio at exact cap
 * multiples, and a word spoken across the boundary loses its start or end
 * (~8 words per boundary on the 366s German tile, 30s chunks 17% below 60s
 * on word recovery; the 2026-08-30 matrix check). The canary path proved
 * silence-aligned cuts lossless by construction; this brings the same
 * benefit to the window paths WITHOUT dropping audio: the cut MOVES to the
 * quietest short frame inside a search window around the ideal position,
 * nothing is removed (plan A of the task; overlap+dedup is the fallback
 * plan B and stays unimplemented).
 *
 * Detector: short-time RMS per [FRAME_MS] frame, hop [FRAME_MS]. A frame
 * counts as silence when its RMS stays under [SILENCE_RELATIVE_FLOOR] times
 * the analysed region's 90th-percentile frame RMS (loudness-relative, so
 * the detector needs no absolute calibration across recording conditions).
 * Among silent frames in the window the one nearest the ideal cut wins;
 * when no frame qualifies, the cut stays exactly where the fixed chunker
 * would put it (the AC's fallback: threshold errors move a cut, they never
 * lose audio).
 *
 * Two consumers share the primitive: the batch path
 * ([cutOffsets], whole-track frames computed once) and the streaming
 * decoder ([placeCut], one region at a time as MediaCodec output
 * accumulates past the cap plus the lookahead).
 *
 * Streaming cost, accepted for prototype A: the decoder holds the 2s
 * lookahead before every flush, so the first chunk of a long file lands
 * 2s of decode later than the fixed-grid emission and the accumulator's
 * steady state is cap+2s of samples (decode runs far ahead of realtime,
 * so the wall-clock cost is decode-bound, not 2s of user waiting).
 */
object SilenceAwareCutPlacer {

    /** Analysis frame and hop in ms: 20ms is long enough to sit inside a
     *  pause between words, short enough to not straddle a plosive. */
    internal const val FRAME_MS = 20

    /** How far BEFORE the ideal cut a silence may sit and still win:
     *  2s matches the VAD merge slack the progressive path already uses
     *  (AudioPreprocessor.vadMergeLimitSeconds reserves 2s under the cap).
     *  BACKWARD-ONLY by design: a forward search would let a chunk exceed
     *  the cap (cap+2s = 32s under whisper's 30s window), and sherpa's
     *  whisper decoder discards everything past 29.5s (2950 frames,
     *  verified at the pinned tag, TASK-718) exactly the tail this
     *  placement exists to save. Cuts only move earlier; chunk counts
     *  can exceed the fixed-grid ceil, never fall under it. */
    internal const val SEARCH_WINDOW_SECONDS = 2

    /** A frame is silence under 10% of the loud 90th-percentile RMS. Voice
     *  messages are near-field speech on quiet floors; 10% separates a
     *  pause from any voiced frame with margin on both sides. */
    internal const val SILENCE_RELATIVE_FLOOR = 0.10

    /** Cuts never move below this distance from the previous cut (or from
     *  the region start): a degenerate long silence must not collapse a
     *  chunk to near-zero duration. */
    internal const val MIN_CHUNK_SECONDS = 5

    /**
     * Cut sample offsets for [samples] at [sampleRate] under a
     * [capSeconds] chunk cap: element 0 is the FIRST cut (end of chunk 0),
     * the array length is chunkCount - 1, and the last chunk runs to the
     * end of the array (never returned as an offset). Consecutive
     * boundaries always span at least [MIN_CHUNK_SECONDS]; the final chunk
     * may be shorter.
     */
    fun cutOffsets(samples: FloatArray, sampleRate: Int, capSeconds: Int): LongArray {
        val capSamples = capSeconds.toLong() * sampleRate
        val total = samples.size.toLong()
        if (total <= capSamples) return LongArray(0)

        val frame = frameSamples(sampleRate)
        val frameCount = samples.size / frame
        if (frameCount == 0) return fixedCuts(total, capSamples)
        val rms = frameRms(samples, frame, frameCount)
        val floor = silenceFloorOf(rms)

        val windowSamples = SEARCH_WINDOW_SECONDS.toLong() * sampleRate
        val cuts = mutableListOf<Long>()
        var prevCut = 0L
        var ideal = capSamples
        while (ideal < total) {
            val cut = chooseCut(
                rms = rms,
                frame = frame,
                ideal = ideal,
                minOffset = maxOf(prevCut + MIN_CHUNK_SECONDS.toLong() * sampleRate, ideal - windowSamples),
                maxOffset = minOf(total - 1, ideal),
                floor = floor,
                fallback = ideal,
            )
            cuts.add(cut)
            prevCut = cut
            ideal = cut + capSamples
        }
        return cuts.toLongArray()
    }

    /**
     * ONE cut for a region that starts at a chunk boundary: [segments]
     * holds every sample decoded since the last emitted chunk (at least
     * the cap, plus the streaming lookahead when the caller buffers one),
     * [regionSize] is the logical sample count across them, and the
     * returned offset is where this region's chunk ends. Reads the
     * segments in place: the old shape merged the whole region into one
     * array just to scan it, doubling the per-chunk copy volume on the
     * path whose buffering profile the TASK-416 OOM work cares about.
     * Falls back to the exact cap when no silent frame qualifies; the
     * search window is the same backward-only clamp [cutOffsets] uses.
     */
    fun placeCut(segments: List<FloatArray>, sampleRate: Int, capSamples: Int, regionSize: Int): Int {
        val cap = capSamples.toLong()
        val frame = frameSamples(sampleRate)
        val frameCount = regionSize / frame
        if (frameCount == 0 || regionSize.toLong() <= cap) return cap.coerceAtMost(regionSize.toLong()).toInt()
        val windowSamples = SEARCH_WINDOW_SECONDS.toLong() * sampleRate
        return chooseFromRms(frameRmsOf(segments, frame, frameCount), frame, cap, cap,
            minOf(regionSize.toLong() - 1, cap),
            maxOf(MIN_CHUNK_SECONDS.toLong() * sampleRate, cap - windowSamples)).toInt()
    }

    private fun chooseFromRms(
        rms: FloatArray,
        frame: Int,
        ideal: Long,
        fallback: Long,
        maxOffset: Long,
        minOffset: Long,
    ): Long = chooseCut(rms, frame, ideal, minOffset, maxOffset, silenceFloorOf(rms), fallback)

    /**
     * The shared choice: the silent frame nearest [ideal] inside
     * [minOffset, maxOffset], cut at the frame START relative to the
     * region the [rms] frames describe. [fallback] when nothing
     * qualifies.
     */
    private fun chooseCut(
        rms: FloatArray,
        frame: Int,
        ideal: Long,
        minOffset: Long,
        maxOffset: Long,
        floor: Float,
        fallback: Long,
    ): Long {
        if (maxOffset <= minOffset || floor <= 0f) return fallback
        var chosen = fallback
        var bestDistance = Long.MAX_VALUE
        val firstFrame = (minOffset / frame).toInt()
        val lastFrame = minOf(rms.size - 1, (maxOffset / frame).toInt())
        for (i in firstFrame..lastFrame) {
            if (rms[i] >= floor) continue
            val start = i.toLong() * frame
            val center = start + frame / 2
            // min on the frame START (the cut lands there, so the
            // MIN_CHUNK floor holds on cut values, not centers); max on
            // the center (distance metric).
            if (start < minOffset || center > maxOffset) continue
            val distance = Math.abs(center - ideal)
            if (distance < bestDistance) {
                bestDistance = distance
                // Cut at the frame START: a pause's tail rides the chunk
                // being closed, the next word starts the next chunk clean.
                chosen = i.toLong() * frame
            }
        }
        return chosen
    }

    private fun frameSamples(sampleRate: Int): Int = sampleRate * FRAME_MS / 1000

    /** Segment-listing twin of [frameRms]: walks the decoder accumulator
     *  in place, no concatenation copy. Frames may straddle segment edges. */
    private fun frameRmsOf(segments: List<FloatArray>, frame: Int, frameCount: Int): FloatArray {
        val rms = FloatArray(frameCount)
        var seg = 0
        var posInSeg = 0
        for (f in 0 until frameCount) {
            var acc = 0.0
            var need = frame
            while (need > 0 && seg < segments.size) {
                val arr = segments[seg]
                val take = minOf(arr.size - posInSeg, need)
                var j = posInSeg
                val end = posInSeg + take
                while (j < end) {
                    val v = arr[j].toDouble()
                    acc += v * v
                    j++
                }
                need -= take
                posInSeg = end
                if (posInSeg == arr.size) {
                    seg++
                    posInSeg = 0
                }
            }
            rms[f] = Math.sqrt(acc / frame).toFloat()
        }
        return rms
    }

    private fun frameRms(samples: FloatArray, frame: Int, frameCount: Int): FloatArray {
        val rms = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var acc = 0.0
            val base = i * frame
            for (j in 0 until frame) {
                val v = samples[base + j].toDouble()
                acc += v * v
            }
            rms[i] = Math.sqrt(acc / frame).toFloat()
        }
        return rms
    }

    /** The loud-percentile reference: sort a copy, read the 90th percentile.
     *  Zero (digital silence track, degenerate input) disables silence
     *  detection and falls back to fixed cuts everywhere. */
    private fun silenceFloorOf(rms: FloatArray): Float {
        val sorted = rms.copyOf().also { it.sort() }
        val p90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        return p90 * SILENCE_RELATIVE_FLOOR.toFloat()
    }

    private fun fixedCuts(total: Long, capSamples: Long): LongArray {
        val cuts = mutableListOf<Long>()
        var ideal = capSamples
        while (ideal < total) {
            cuts.add(ideal)
            ideal += capSamples
        }
        return cuts.toLongArray()
    }
}
