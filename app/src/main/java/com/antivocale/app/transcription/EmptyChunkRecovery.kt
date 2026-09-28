package com.antivocale.app.transcription

import android.util.Log
import com.antivocale.app.manager.EngineWedgeTimeoutException
import com.antivocale.app.util.concatFloatArrays
import kotlinx.coroutines.CancellationException

/**
 * TASK-664 (GH #119): the bounded recovery ladder for chunks that decode to
 * EMPTY text on the offline chunk paths. A blank chunk used to land in the
 * blank count (or fail the whole clip when it was the only chunk) even when a
 * trivial re-feed recovers it; after the ladder, the residual blank count is
 * real signal for the root-cause hunt (GH #96, GH #2).
 *
 * The caller spends its first decode, sees a blank SUCCESS, and hands the
 * chunk here with whatever neighbor audio it can offer. The ladder never
 * exceeds [MAX_EXTRA_DECODES] extra decodes, and a chunk still empty after it
 * stays an honest blank: no success inflation, no unbounded retry.
 *
 * Rungs, first recovery wins:
 * 1. The same samples on a fresh decode call (a fresh sherpa stream can
 *    answer a nondeterministic no-token decode with text).
 * 2. Context re-feed: neighbor overlap ([NEIGHBOR_OVERLAP_SECONDS] from each
 *    adjacent chunk) when any neighbor exists; the recovered text is trimmed
 *    to the chunk's own time window by token timestamps so neighbor words are
 *    never duplicated in the assembled transcript. A clip with no neighbors
 *    (the single-chunk voice note, a total loss before this ladder) is re-fed
 *    with [PAD_SECONDS] of silence either side instead: padding decodes no
 *    words, so the whole text is the chunk's own.
 *
 * A rung whose model returns text WITHOUT token timestamps is unusable (the
 * words cannot be attributed to this chunk) and is spent without adopting its
 * text. Pure Kotlin over a decode lambda so tests exercise the ladder without
 * the native engine (the vocaphone SherpaEmptyChunkRecovery arrangement).
 */
internal object EmptyChunkRecovery {

    /** Extra decodes beyond the caller's first pass. The ladder never exceeds this. */
    const val MAX_EXTRA_DECODES = 2

    /** Context borrowed from each adjacent chunk for the overlap re-feed. */
    const val NEIGHBOR_OVERLAP_SECONDS = 1

    /** Silence added either side when no neighbor exists (the single-chunk arm). */
    const val PAD_SECONDS = 0.5f

    private const val TAG = "EmptyChunkRecovery"

    sealed interface Outcome {
        /** A rung produced usable chunk-owned text. */
        data class Recovered(val result: TranscriptionResult) : Outcome

        /** Every rung is spent and the chunk stays an honest blank. */
        object StillEmpty : Outcome

        /**
         * A rung hit the wedge marker: the engine is dead, retrying burns a
         * full generation ceiling (TASK-606 F2), so the ladder stops and the
         * caller's existing wedge abort owns the run.
         */
        data class EngineWedged(val error: EngineWedgeTimeoutException) : Outcome
    }

    /** The last [NEIGHBOR_OVERLAP_SECONDS] of a neighbor chunk. */
    fun tail(samples: FloatArray, sampleRate: Int): FloatArray {
        val count = minOf(samples.size, sampleRate * NEIGHBOR_OVERLAP_SECONDS)
        return samples.copyOfRange(samples.size - count, samples.size)
    }

    /** The first [NEIGHBOR_OVERLAP_SECONDS] of a neighbor chunk. */
    fun head(samples: FloatArray, sampleRate: Int): FloatArray {
        val count = minOf(samples.size, sampleRate * NEIGHBOR_OVERLAP_SECONDS)
        return samples.copyOfRange(0, count)
    }

    /**
     * Runs the ladder for one first-pass-empty [chunk]. [previousChunk] and
     * [nextChunk] are the RAW adjacent chunks; the ladder bounds them to the
     * overlap seconds itself, so no caller can feed unbounded audio past the
     * family chunk cap. Either may be null or empty (a boundary chunk, the
     * pipeline's held tail, a single-chunk clip). Blank-when-recovered is
     * impossible: a rung counts only when its text is non-blank.
     */
    suspend fun recover(
        chunk: FloatArray,
        sampleRate: Int,
        previousChunk: FloatArray?,
        nextChunk: FloatArray?,
        decode: suspend (FloatArray) -> Result<TranscriptionResult>,
    ): Outcome {
        if (chunk.isEmpty()) return Outcome.StillEmpty
        var wedge: EngineWedgeTimeoutException? = null

        // One rung: null means spent (blank, unusable, or errored); the wedge
        // marker is the only error that escapes (see Outcome.EngineWedged).
        suspend fun rung(feed: FloatArray): TranscriptionResult? {
            val result = try {
                decode(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineWedgeTimeoutException) {
                wedge = e
                return null
            } catch (e: Throwable) {
                Log.w(TAG, "recovery rung decode threw; rung spent", e)
                return null
            }
            // The backends report timeouts as failure Results, not throws
            // (the per-chunk folds match on exactly this type).
            (result.exceptionOrNull() as? EngineWedgeTimeoutException)?.let {
                wedge = it
                return null
            }
            return result.getOrNull()?.takeIf { it.text.isNotBlank() }
        }

        // Rung 1: fresh decode of the same samples.
        rung(chunk)?.let { return Outcome.Recovered(it) }
        wedge?.let { return Outcome.EngineWedged(it) }

        val prev = previousChunk?.let { tail(it, sampleRate) } ?: EMPTY_SAMPLES
        val next = nextChunk?.let { head(it, sampleRate) } ?: EMPTY_SAMPLES
        val contextRung = if (prev.isNotEmpty() || next.isNotEmpty()) {
            overlapRung(chunk, prev, next, sampleRate) { rung(it) }
        } else {
            padRung(chunk, sampleRate) { rung(it) }
        }
        return contextRung
            ?: wedge?.let { Outcome.EngineWedged(it) }
            ?: Outcome.StillEmpty
    }

    /**
     * Rung 2 with neighbors: decode prevTail + chunk + nextHead and keep only
     * the chunk's own time window. Token start times decide ownership (a word
     * belongs where it starts); kept tokens are rebased to chunk-relative
     * times for the cue paths. Null when the rung is spent.
     */
    private inline fun overlapRung(
        chunk: FloatArray,
        prev: FloatArray,
        next: FloatArray,
        sampleRate: Int,
        decodeRung: (FloatArray) -> TranscriptionResult?,
    ): Outcome? {
        val combined = decodeRung(concatFloatArrays(prev, chunk, next)) ?: return null
        if (combined.tokens.isEmpty()) {
            // The words cannot be attributed to this chunk; adopting the text
            // would duplicate neighbor words already transcribed elsewhere.
            Log.i(TAG, "overlap re-feed returned no token timestamps; " +
                "${combined.text.length} chars unusable, chunk stays empty")
            return null
        }
        val windowStartMs = prev.size * 1000L / sampleRate
        val windowEndMs = windowStartMs + chunk.size * 1000L / sampleRate
        val inWindow = { t: TimedToken -> t.startMs >= windowStartMs && t.startMs < windowEndMs }
        val first = combined.tokens.indexOfFirst(inWindow)
        if (first < 0) {
            Log.i(TAG, "overlap re-feed decoded only neighbor audio; chunk stays empty")
            return null
        }
        val last = combined.tokens.indexOfLast(inWindow)
        val text = SentenceCueBuilder.sliceText(combined.tokens, combined.text.trim(), first, last)
        if (text.isBlank()) return null
        return Outcome.Recovered(combined.copy(
            text = text,
            tokens = shiftTokens(
                combined.tokens.subList(first, last + 1), windowStartMs, clampStart = false)))
    }

    /**
     * Rung 2 without neighbors: [PAD_SECONDS] of silence either side moves
     * very short speech inside the encoder's context (the vocaphone
     * arrangement). Padding decodes no words, so the text and tokens are the
     * chunk's own; tokens just shift by the leading pad.
     */
    private inline fun padRung(
        chunk: FloatArray,
        sampleRate: Int,
        decodeRung: (FloatArray) -> TranscriptionResult?,
    ): Outcome? {
        val pad = FloatArray((sampleRate * PAD_SECONDS).toInt())
        val padded = decodeRung(concatFloatArrays(pad, chunk, pad)) ?: return null
        val padMs = pad.size * 1000L / sampleRate
        return Outcome.Recovered(padded.copy(
            tokens = shiftTokens(padded.tokens, padMs, clampStart = true)))
    }

    /**
     * Rebases token times by [byMs]. [clampStart] is the rungs' one
     * deliberate asymmetry: the overlap rung keeps only window-selected
     * tokens, whose shifted starts are non-negative by construction, while
     * the pad rung keeps every token and clamps one that starts inside the
     * leading pad to the chunk's own start.
     */
    private fun shiftTokens(
        tokens: List<TimedToken>,
        byMs: Long,
        clampStart: Boolean,
    ): List<TimedToken> = tokens.map {
        val shiftedStart = it.startMs - byMs
        it.copy(
            startMs = if (clampStart) shiftedStart.coerceAtLeast(0L) else shiftedStart,
            endMs = (it.endMs - byMs).coerceAtLeast(0L),
        )
    }

    private val EMPTY_SAMPLES = FloatArray(0)
}
