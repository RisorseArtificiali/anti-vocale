package com.antivocale.app.transcription.diarization

import com.antivocale.app.transcription.TranscriptionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * TASK-1060.3: the windowed diarization pass for audio too long to hold in
 * heap whole (GH #83 multi-hour recordings). It consumes the 16 kHz decode
 * stream, keeps a rolling buffer of one window, and per window segments
 * the audio, cuts the core-region segments into pieces and embeds them;
 * the window's audio is reused for the next one, so only the small piece
 * embeddings outlive it. One global [PieceClustering] pass then assigns
 * the speakers (the TASK-1060.2 spike verdict: per-window clustering
 * merges speakers that no later reconcile can split again).
 *
 * @param segmentWindow the window's speech segments in seconds from the
 *        window's start; the speaker ids a diarizer assigns are discarded
 * @param embedWindow one raw embedding per sample range of the window, null
 *        where the range is too short to embed
 */
class WindowedDiarizer(
    private val params: WindowedDiarizationParams,
    private val segmentWindow: (samples: FloatArray) -> List<TimeSpan>,
    private val embedWindow: (samples: FloatArray, ranges: List<IntRange>) -> List<FloatArray?>,
) {
    companion object {
        const val SAMPLE_RATE = 16000

        /** The buffer's first size when the decode length is unknown: one minute. */
        private const val INITIAL_BUFFER_SAMPLES = 60L * SAMPLE_RATE

        /** Finite values and a norm that stays finite (and nonzero) in Float. */
        internal fun isUsable(embedding: FloatArray): Boolean {
            var squares = 0.0
            for (v in embedding) {
                if (!v.isFinite()) return false
                squares += v.toDouble() * v
            }
            return squares > 0.0 && squares.toFloat().isFinite()
        }

        /**
         * The native pass. Each window creates, uses and releases its own
         * diarizer, then its own embedding extractor, so two titanet
         * sessions are never alive at once (the TASK-679 OOM class). This is
         * the one place that lifecycle lives (TASK-1060.5 measures its
         * per-window load cost).
         *
         * @param onSegmentProgress the current window's segmentation
         *        progress (chunks done, chunk total), from the native run
         */
        fun native(
            segmentationModel: File,
            embeddingModel: File,
            numThreads: Int,
            params: WindowedDiarizationParams = WindowedDiarizationParams(),
            onSegmentProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        ) = WindowedDiarizer(
            params,
            segmentWindow = { samples ->
                val diarizer = SpeakerDiarizer.create(segmentationModel, embeddingModel, numThreads)
                    .getOrThrow()
                try {
                    diarizer.diarize(samples, SAMPLE_RATE, onSegmentProgress)
                        .map { TimeSpan(it.startSec.toDouble(), it.endSec.toDouble()) }
                } finally {
                    diarizer.release()
                }
            },
            embedWindow = { samples, ranges ->
                val embeddings = SpeakerEmbeddings.create(embeddingModel, numThreads).getOrThrow()
                try {
                    // Only the too-short verdict drops a piece; any other
                    // engine failure propagates so a wedged extractor fails
                    // the pass instead of yielding a silently empty result.
                    ranges.map { range ->
                        try {
                            embeddings.compute(
                                samples.copyOfRange(range.first, range.last + 1), SAMPLE_RATE)
                        } catch (_: TranscriptionException.NativeError) {
                            null
                        }
                    }
                } finally {
                    embeddings.release()
                }
            },
        )
    }

    /** The number of windows [diarize] runs over a decode of [totalSamples]. */
    fun windowCount(totalSamples: Long): Int =
        PieceClustering.planWindows(totalSamples, SAMPLE_RATE, params).size

    /**
     * @param chunks the contiguous 16 kHz decode, in order
     * @param totalSamples the decode's length when known, for the window count
     * @param onWindowDone called after each window with the windows done so
     *        far and the total when [totalSamples] is known
     */
    suspend fun diarize(
        chunks: Flow<FloatArray>,
        totalSamples: Long? = null,
        onWindowDone: (done: Int, total: Int?) -> Unit = { _, _ -> },
    ): WindowedDiarization {
        val windowCount = totalSamples?.let(::windowCount)
        val windowSamples = PieceClustering.windowSamples(SAMPLE_RATE, params)
        val stride = windowSamples - PieceClustering.overlapSamples(SAMPLE_RATE, params)
        val pieces = ArrayList<Piece>()
        var unusable = 0
        // TASK-1060.7: sized to the decode when it is known, so a resumed or
        // retention-abandoned short file does not pay for a whole window; it
        // doubles up to the window when the decode is unknown or longer than
        // estimated.
        var buffer = FloatArray(
            minOf(windowSamples.toLong(), totalSamples ?: INITIAL_BUFFER_SAMPLES).coerceAtLeast(1L).toInt())
        var size = 0
        var bufferStart = 0L
        var done = 0

        fun runWindow(samples: FloatArray, isLast: Boolean) {
            val plan = PieceClustering.window(
                bufferStart, bufferStart + samples.size, isLast, SAMPLE_RATE, params)
            pieces += embedPieces(samples, plan) { unusable++ }
            // An estimated totalSamples can undercount the real decode.
            onWindowDone(++done, windowCount?.let { maxOf(it, done) })
        }

        chunks.collect { chunk ->
            currentCoroutineContext().ensureActive()
            var offset = 0
            while (offset < chunk.size) {
                // A full window with more audio still arriving cannot be the last one.
                if (size == windowSamples) {
                    runWindow(buffer, isLast = false)
                    buffer.copyInto(buffer, 0, stride, size)
                    size -= stride
                    bufferStart += stride
                }
                val n = minOf(chunk.size - offset, windowSamples - size)
                if (size + n > buffer.size) {
                    // Unknown length: past half the window, grow straight to
                    // it, so a doubled buffer is at most half a window when
                    // copied into the full one (1.5 windows at the peak).
                    // Known length (an undercounted estimate): keep doubling
                    // toward the window, so a short file never pays for a
                    // whole one; that growth stays under 2 windows.
                    val grown = minOf(windowSamples, maxOf(buffer.size * 2, size + n))
                    buffer = buffer.copyOf(
                        if (totalSamples == null && grown > windowSamples / 2) windowSamples else grown)
                }
                chunk.copyInto(buffer, size, offset, offset + n)
                size += n
                offset += n
            }
        }
        // The diarizer takes the window as an exact array, so a partial last
        // window is briefly held twice.
        if (size > 0) runWindow(if (size == buffer.size) buffer else buffer.copyOf(size), isLast = true)
        val diarization = PieceClustering.diarize(pieces, params)
        return WindowedDiarization(
            diarization.segments, diarization.centroids,
            diarization.stats.copy(unusableEmbeddings = unusable), diarization.unlabeledSpans)
    }

    private fun embedPieces(samples: FloatArray, plan: WindowPlan, onUnusable: () -> Unit): List<Piece> {
        val offsetSec = plan.startSample.toDouble() / SAMPLE_RATE
        val spans = segmentWindow(samples)
            .map { TimeSpan(offsetSec + it.startSec, offsetSec + it.endSec) }
            .mapNotNull(plan::clip)
            .flatMap { PieceClustering.cutPieces(it, params.pieceSec) }
        val ranges = spans.map { span ->
            val from = ((span.startSec * SAMPLE_RATE).toLong() - plan.startSample)
                .coerceIn(0L, samples.size.toLong()).toInt()
            val to = ((span.endSec * SAMPLE_RATE).toLong() - plan.startSample)
                .coerceIn(0L, samples.size.toLong()).toInt()
            from until to
        }
        return spans.zip(embedWindow(samples, ranges)).mapNotNull { (span, embedding) ->
            // A non-finite or unnormalizable embedding would poison every
            // cosine it meets (NaN) or sit at distance 1 from everything.
            embedding?.let { e ->
                if (isUsable(e)) Piece(span, PieceClustering.normalized(e)) else null.also { onUnusable() }
            }
        }
    }
}
