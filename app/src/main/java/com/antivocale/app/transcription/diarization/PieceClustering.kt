package com.antivocale.app.transcription.diarization

import kotlin.math.sqrt

/**
 * TASK-1060.3: the parameters of the windowed diarization pass. Every
 * default is the TASK-1060.2 spike's recommendation (zero merges on all
 * six window x composite cells at threshold 0.8; the first merge appears
 * a full 0.1 step higher), so change them only against new spike numbers.
 */
data class WindowedDiarizationParams(
    val windowSec: Double = 10 * 60.0,
    val overlapSec: Double = 30.0,
    val pieceSec: Double = 3.0,
    val threshold: Float = SpeakerDiarizer.AUTO_THRESHOLD,
    val absorbFloorSec: Double = 30.0,
    /**
     * Bound on the clustering's condensed distance matrix (n(n-1)/2 floats:
     * 5000 pieces is about 50 MB). Above it, adjacent pieces of one segment
     * are merged pairwise (embeddings averaged) until the count fits; at
     * about 1200 pieces per hour of speech (TASK-1060.2 composites) the
     * 4 h streaming ceiling stays under it at full resolution.
     */
    val maxPieces: Int = 5000,
) {
    init {
        // At least a second of stride, so neither the planner nor the
        // streaming driver can stall on a window that never advances.
        require(overlapSec >= 0 && windowSec - overlapSec >= 1.0) {
            "the window must exceed the overlap by at least 1 s"
        }
        require(pieceSec >= 1.0) { "pieces under 1 s would all be dropped as too short" }
        require(maxPieces >= 2) { "maxPieces must allow a clustering" }
    }
}

/** A time span in absolute seconds from the audio's start. */
data class TimeSpan(val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = endSec - startSec
}

/**
 * One window of the decode timeline. The window owns its core region
 * [coreLoSec, coreHiSec): the overlap halves belong to its neighbours, and
 * every segment is clipped to the core, so a turn crossing an overlap is
 * covered exactly once, its two halves embedded by the two windows (the
 * midpoint ownership the spike first measured could keep a truncated turn
 * in both windows; clipping measured equal or better, TASK-1060.3 notes).
 */
data class WindowPlan(
    val startSample: Long,
    val endSample: Long,
    val coreLoSec: Double,
    val coreHiSec: Double,
) {
    fun clip(segment: TimeSpan): TimeSpan? {
        val start = maxOf(segment.startSec, coreLoSec)
        val end = minOf(segment.endSec, coreHiSec)
        return if (end > start) TimeSpan(start, end) else null
    }
}

/** One embedded piece of a segment; [embedding] is L2-normalized. */
class Piece(val span: TimeSpan, val embedding: FloatArray)

/**
 * TASK-1060.4: the windowed pass's output. [centroids] maps each dense
 * speaker id of [segments] to the L2-normalized sum of that speaker's piece
 * embeddings: the windowed pass never holds the whole file's samples, so
 * the naming pass matches these voiceprints instead of re-embedding audio.
 */
class WindowedDiarization(
    val segments: List<DiarizedSegment>,
    val centroids: Map<Int, FloatArray>,
)

/**
 * TASK-1060.3: the pure core of the windowed diarization pass (TASK-1060.2
 * verdict): windows give segmentation only, core-region segments are cut
 * into short pieces, and ONE global clustering over every piece of the file
 * assigns the speakers. Reference behavior: eval/diarization_windowed.py
 * (`embed_pieces`, `absorb_small`).
 */
object PieceClustering {

    /** A final piece shorter than this joins the previous one. */
    private const val MIN_LEFTOVER_SEC = 1.0

    /** Pieces shorter than this are dropped (too short to embed reliably). */
    private const val MIN_PIECE_SEC = 0.5

    fun window(
        startSample: Long,
        endSample: Long,
        isLast: Boolean,
        sampleRate: Int,
        params: WindowedDiarizationParams,
    ): WindowPlan {
        val half = params.overlapSec / 2
        return WindowPlan(
            startSample = startSample,
            endSample = endSample,
            coreLoSec = startSample.toDouble() / sampleRate + if (startSample > 0) half else 0.0,
            coreHiSec = endSample.toDouble() / sampleRate - if (isLast) 0.0 else half,
        )
    }

    /** The windows covering [totalSamples], each starting one overlap before the previous end. */
    fun planWindows(
        totalSamples: Long,
        sampleRate: Int,
        params: WindowedDiarizationParams,
    ): List<WindowPlan> {
        val windowSamples = windowSamples(sampleRate, params)
        val overlapSamples = overlapSamples(sampleRate, params)
        val plans = ArrayList<WindowPlan>()
        var start = 0L
        while (start < totalSamples) {
            val end = minOf(totalSamples, start + windowSamples)
            plans += window(start, end, isLast = end == totalSamples, sampleRate, params)
            start = end - overlapSamples
            if (end == totalSamples) break
        }
        return plans
    }

    fun windowSamples(sampleRate: Int, params: WindowedDiarizationParams): Int =
        (params.windowSec * sampleRate).toInt()

    /** The one overlap derivation the planner and the streaming driver share. */
    fun overlapSamples(sampleRate: Int, params: WindowedDiarizationParams): Int =
        (params.overlapSec * sampleRate).toInt()

    /**
     * Cuts a segment into pieces of [pieceSec] from its start; a final
     * leftover under 1 s joins the previous piece and pieces under 0.5 s
     * are dropped.
     */
    fun cutPieces(segment: TimeSpan, pieceSec: Double): List<TimeSpan> {
        val cuts = ArrayList<Double>()
        var t = segment.startSec
        while (t < segment.endSec) {
            cuts += t
            t = segment.startSec + cuts.size * pieceSec
        }
        cuts += segment.endSec
        if (cuts.size > 2 && cuts[cuts.size - 1] - cuts[cuts.size - 2] < MIN_LEFTOVER_SEC) {
            cuts.removeAt(cuts.size - 2)
        }
        return cuts.zipWithNext { a, b -> TimeSpan(a, b) }
            .filter { it.durationSec >= MIN_PIECE_SEC }
    }

    /**
     * Port of sherpa-onnx v2.0.2 FastClustering with a threshold
     * (fast-clustering.cc): row-normalize, cosine dissimilarity 1 - dot
     * clamped at 0, fastcluster's nearest-neighbor-chain COMPLETE linkage,
     * then cutree_cdist, which applies exactly the merges whose height is
     * STRICTLY below the threshold (it stops at the first `height >= cdist`).
     * O(n^2) time and one condensed float matrix of n(n-1)/2 entries.
     *
     * @return a cluster label per row; only the partition is meaningful.
     */
    fun completeLinkage(embeddings: List<FloatArray>, threshold: Float): IntArray {
        val n = embeddings.size
        if (n <= 1) return IntArray(n)
        val rows = embeddings.map(::normalized)
        val pairs = n.toLong() * (n - 1) / 2
        require(pairs <= Int.MAX_VALUE) { "too many pieces for one condensed matrix: $n" }
        // Float distances (sherpa keeps doubles): halves the matrix; only a
        // height within float rounding of the threshold could cut differently.
        val distances = FloatArray(pairs.toInt())
        var k = 0
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                distances[k++] = (1.0 - dot(rows[i], rows[j])).coerceAtLeast(0.0).toFloat()
            }
        }
        val merges = nnChainComplete(n, distances)
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) c = parent[c].also { parent[c] = r }
            return r
        }
        // Complete linkage heights are monotone up the dendrogram, so the
        // merges below the threshold form closed subtrees and their union
        // is the cutree_cdist partition, whatever order the chain found them.
        for (merge in merges) {
            if (merge.height < threshold.toDouble()) parent[find(merge.a)] = find(merge.b)
        }
        return IntArray(n) { find(it) }
    }

    private class Merge(val a: Int, val b: Int, val height: Double)

    /** fastcluster's NN_chain_core for METHOD_METR_COMPLETE, tie-breaks included. */
    private fun nnChainComplete(n: Int, d: FloatArray): List<Merge> {
        fun index(i: Int, j: Int): Int =
            (n.toLong() * i - i.toLong() * (i + 1) / 2 + j - i - 1).toInt()
        val succ = IntArray(n + 1) { it + 1 }
        val pred = IntArray(n + 1) { it - 1 }
        var activeStart = 0
        fun remove(idx: Int) {
            if (idx == activeStart) {
                activeStart = succ[idx]
            } else {
                succ[pred[idx]] = succ[idx]
                pred[succ[idx]] = pred[idx]
            }
        }

        val chain = IntArray(n)
        var tip = 0
        val merges = ArrayList<Merge>(n - 1)
        repeat(n - 1) {
            var idx1: Int
            var idx2: Int
            var min: Float
            if (tip <= 3) {
                idx1 = activeStart
                chain[0] = idx1
                tip = 1
                idx2 = succ[idx1]
                min = d[index(idx1, idx2)]
                var i = succ[idx2]
                while (i < n) {
                    if (d[index(idx1, i)] < min) {
                        min = d[index(idx1, i)]
                        idx2 = i
                    }
                    i = succ[i]
                }
            } else {
                tip -= 3
                idx1 = chain[tip - 1]
                idx2 = chain[tip]
                min = if (idx1 < idx2) d[index(idx1, idx2)] else d[index(idx2, idx1)]
            }
            do {
                chain[tip] = idx2
                var i = activeStart
                while (i < idx2) {
                    if (d[index(i, idx2)] < min) {
                        min = d[index(i, idx2)]
                        idx1 = i
                    }
                    i = succ[i]
                }
                i = succ[idx2]
                while (i < n) {
                    if (d[index(idx2, i)] < min) {
                        min = d[index(idx2, i)]
                        idx1 = i
                    }
                    i = succ[i]
                }
                idx2 = idx1
                idx1 = chain[tip++]
            } while (idx2 != chain[tip - 2])

            merges += Merge(idx1, idx2, min.toDouble())
            val lo = minOf(idx1, idx2)
            val hi = maxOf(idx1, idx2)
            remove(lo)
            var i = activeStart
            while (i < lo) {
                val target = index(i, hi)
                d[target] = maxOf(d[target], d[index(i, lo)])
                i = succ[i]
            }
            while (i < hi) {
                val target = index(i, hi)
                d[target] = maxOf(d[target], d[index(lo, i)])
                i = succ[i]
            }
            i = succ[hi]
            while (i < n) {
                val target = index(hi, i)
                d[target] = maxOf(d[target], d[index(lo, i)])
                i = succ[i]
            }
        }
        return merges
    }

    /**
     * TASK-1061 absorption: clusters whose total piece time is under
     * [floorSec] join the large cluster whose mean embedding is closest by
     * cosine to their own. When no cluster reaches the floor, the labels
     * are returned unchanged.
     */
    fun absorbSmall(labels: IntArray, pieces: List<Piece>, floorSec: Double): IntArray {
        if (pieces.isEmpty()) return labels
        val talk = LinkedHashMap<Int, Double>()
        val sums = HashMap<Int, FloatArray>()
        labels.forEachIndexed { i, label ->
            talk[label] = (talk[label] ?: 0.0) + pieces[i].span.durationSec
            val sum = sums.getOrPut(label) { FloatArray(pieces[i].embedding.size) }
            val e = pieces[i].embedding
            for (k in sum.indices) sum[k] += e[k]
        }
        val big = talk.filterValues { it >= floorSec }.keys.toList()
        if (big.isEmpty()) return labels
        val centroids = big.map { normalized(sums.getValue(it)) }
        // The small cluster's raw sum suffices: its norm scales every
        // candidate's score alike, so the argmax is the cosine argmax.
        val target = talk.filterValues { it < floorSec }.keys.associateWith { label ->
            val own = sums.getValue(label)
            big[centroids.indices.maxBy { dot(centroids[it], own) }]
        }
        return IntArray(labels.size) { target[labels[it]] ?: labels[it] }
    }

    /**
     * Pieces plus their cluster labels as [DiarizedSegment]s in time order:
     * consecutive same-speaker pieces that touch merge back into one span,
     * ids are dense in first-appearance order, confidence is unavailable.
     */
    fun assemble(pieces: List<Piece>, labels: IntArray): List<DiarizedSegment> =
        SpeakerLabeler.denseSegments(assembleRaw(pieces, labels))

    /** [assemble] before the dense renumbering: speakers keep their cluster labels. */
    private fun assembleRaw(pieces: List<Piece>, labels: IntArray): List<DiarizedSegment> {
        // Spans stay Double until emitted: a Float-rounded end compared with
        // the next piece's exact start would split touching pieces of one turn.
        class Open(val speaker: Int, val startSec: Double, var endSec: Double)
        val segments = ArrayList<Open>()
        // Per speaker, so an overlapping piece of another speaker (overlapped
        // speech) does not break the first speaker's contiguous turn.
        val openBySpeaker = HashMap<Int, Open>()
        for (i in pieces.indices.sortedBy { pieces[it].span.startSec }) {
            val span = pieces[i].span
            val open = openBySpeaker[labels[i]]
            if (open != null && span.startSec <= open.endSec) {
                open.endSec = maxOf(open.endSec, span.endSec)
            } else {
                Open(labels[i], span.startSec, span.endSec).also {
                    segments += it
                    openBySpeaker[labels[i]] = it
                }
            }
        }
        return segments.map { DiarizedSegment(it.startSec.toFloat(), it.endSec.toFloat(), it.speaker, null) }
    }

    /**
     * Halves the piece count until it fits [maxPieces] by merging each
     * piece with the next when the two are contiguous cuts of one segment
     * (the embedding becomes the normalized sum). Returns the input when it
     * already fits or when no contiguous pair is left to merge.
     */
    fun coarsen(pieces: List<Piece>, maxPieces: Int): List<Piece> {
        var current = pieces
        while (current.size > maxPieces) {
            val next = ArrayList<Piece>(current.size / 2 + 1)
            var i = 0
            while (i < current.size) {
                val a = current[i]
                val b = current.getOrNull(i + 1)
                if (b != null && b.span.startSec == a.span.endSec) {
                    val sum = FloatArray(a.embedding.size) { a.embedding[it] + b.embedding[it] }
                    next += Piece(TimeSpan(a.span.startSec, b.span.endSec), normalized(sum))
                    i += 2
                } else {
                    next += a
                    i += 1
                }
            }
            if (next.size == current.size) break
            current = next
        }
        return current
    }

    /**
     * Global clustering, absorption and assembly over every piece of the
     * file, plus each final speaker's centroid keyed by its dense id.
     */
    fun diarize(pieces: List<Piece>, params: WindowedDiarizationParams): WindowedDiarization {
        val bounded = coarsen(pieces, params.maxPieces)
        val labels = absorbSmall(
            completeLinkage(bounded.map { it.embedding }, params.threshold),
            bounded, params.absorbFloorSec)
        val raw = assembleRaw(bounded, labels)
        // denseSegments owns the numbering; the centroid keys read its
        // raw->dense map instead of re-deriving it inline.
        val dense = SpeakerLabeler.denseSegments(raw)
        val denseIdByRaw = raw.map { it.speaker }.zip(dense.map { it.speaker }).toMap()
        val sums = HashMap<Int, FloatArray>()
        bounded.forEachIndexed { i, piece ->
            val sum = sums.getOrPut(denseIdByRaw.getValue(labels[i])) { FloatArray(piece.embedding.size) }
            for (k in sum.indices) sum[k] += piece.embedding[k]
        }
        return WindowedDiarization(
            dense,
            sums.mapValues { (_, sum) -> normalized(sum) },
        )
    }

    fun normalized(vector: FloatArray): FloatArray {
        val norm = sqrt(dot(vector, vector)).toFloat()
        return FloatArray(vector.size) { vector[it] / (norm + 1e-9f) }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.toDouble()
    }
}
