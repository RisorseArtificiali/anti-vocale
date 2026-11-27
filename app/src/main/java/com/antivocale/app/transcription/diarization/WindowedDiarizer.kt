package com.antivocale.app.transcription.diarization

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

        /**
         * The native pass. Each window creates, uses and releases its own
         * diarizer, then its own embedding extractor, so two titanet
         * sessions are never alive at once (the TASK-679 OOM class). This is
         * the one place that lifecycle lives (TASK-1060.5 measures its
         * per-window load cost).
         */
        fun native(
            segmentationModel: File,
            embeddingModel: File,
            numThreads: Int,
            params: WindowedDiarizationParams = WindowedDiarizationParams(),
        ) = WindowedDiarizer(
            params,
            segmentWindow = { samples ->
                val diarizer = SpeakerDiarizer.create(segmentationModel, embeddingModel, numThreads)
                    .getOrThrow()
                try {
                    diarizer.diarize(samples, SAMPLE_RATE)
                        .map { TimeSpan(it.startSec.toDouble(), it.endSec.toDouble()) }
                } finally {
                    diarizer.release()
                }
            },
            embedWindow = { samples, ranges ->
                val embeddings = SpeakerEmbeddings.create(embeddingModel, numThreads).getOrThrow()
                try {
                    ranges.map { range ->
                        runCatching {
                            embeddings.compute(
                                samples.copyOfRange(range.first, range.last + 1), SAMPLE_RATE)
                        }.getOrNull()
                    }
                } finally {
                    embeddings.release()
                }
            },
        )
    }

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
    ): List<DiarizedSegment> {
        val windowCount = totalSamples?.let {
            PieceClustering.planWindows(it, SAMPLE_RATE, params).size
        }
        val windowSamples = PieceClustering.windowSamples(SAMPLE_RATE, params)
        val stride = windowSamples - (params.overlapSec * SAMPLE_RATE).toInt()
        val pieces = ArrayList<Piece>()
        var buffer = FloatArray(0)
        var size = 0
        var bufferStart = 0L
        var done = 0

        fun runWindow(samples: FloatArray, isLast: Boolean) {
            val plan = PieceClustering.window(
                bufferStart, bufferStart + samples.size, isLast, SAMPLE_RATE, params)
            pieces += embedPieces(samples, plan)
            onWindowDone(++done, windowCount)
        }

        chunks.collect { chunk ->
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
                if (buffer.size < size + n) {
                    buffer = buffer.copyOf(minOf(windowSamples, maxOf(size + n, buffer.size * 2)))
                }
                chunk.copyInto(buffer, size, offset, offset + n)
                size += n
                offset += n
            }
        }
        if (size > 0) runWindow(if (size == buffer.size) buffer else buffer.copyOf(size), isLast = true)
        return PieceClustering.diarize(pieces, params)
    }

    private fun embedPieces(samples: FloatArray, plan: WindowPlan): List<Piece> {
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
            embedding?.let { Piece(span, PieceClustering.normalized(it)) }
        }
    }
}
