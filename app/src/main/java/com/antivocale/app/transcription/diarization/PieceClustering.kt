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
    val threshold: Float = 0.8f,
    val absorbFloorSec: Double = 30.0,
) {
    init {
        require(windowSec > 0 && overlapSec >= 0 && overlapSec < windowSec) {
            "overlap must be shorter than the window"
        }
        require(pieceSec > 0) { "pieceSec must be positive" }
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
        val overlapSamples = (params.overlapSec * sampleRate).toLong()
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
        val distances = FloatArray(n * (n - 1) / 2)
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
        fun index(i: Int, j: Int): Int = n * i - i * (i + 1) / 2 + j - i - 1
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
        val talk = LinkedHashMap<Int, Double>()
        labels.forEachIndexed { i, label ->
            talk[label] = (talk[label] ?: 0.0) + pieces[i].span.durationSec
        }
        val big = talk.filterValues { it >= floorSec }.keys.toList()
        if (big.isEmpty()) return labels
        val centroids = big.map { normalized(mean(labels, pieces, it)) }
        val target = HashMap<Int, Int>()
        for ((label, seconds) in talk) {
            if (seconds >= floorSec) continue
            val own = mean(labels, pieces, label)
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            centroids.forEachIndexed { c, centroid ->
                val score = dot(centroid, own)
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            target[label] = big[best]
        }
        return IntArray(labels.size) { target[labels[it]] ?: labels[it] }
    }

    /**
     * Pieces plus their cluster labels as [DiarizedSegment]s in time order:
     * consecutive same-speaker pieces that touch merge back into one span,
     * ids are dense in first-appearance order, confidence is unavailable.
     */
    fun assemble(pieces: List<Piece>, labels: IntArray): List<DiarizedSegment> {
        val order = pieces.indices.sortedBy { pieces[it].span.startSec }
        val merged = ArrayList<DiarizedSegment>()
        for (i in order) {
            val span = pieces[i].span
            val last = merged.lastOrNull()
            if (last != null && last.speaker == labels[i] && span.startSec <= last.endSec) {
                merged[merged.size - 1] = last.copy(endSec = maxOf(last.endSec, span.endSec.toFloat()))
            } else {
                merged += DiarizedSegment(span.startSec.toFloat(), span.endSec.toFloat(), labels[i], null)
            }
        }
        return SpeakerLabeler.denseSegments(merged)
    }

    /** Global clustering, absorption and assembly over every piece of the file. */
    fun diarize(pieces: List<Piece>, params: WindowedDiarizationParams): List<DiarizedSegment> {
        val labels = completeLinkage(pieces.map { it.embedding }, params.threshold)
        return assemble(pieces, absorbSmall(labels, pieces, params.absorbFloorSec))
    }

    fun normalized(vector: FloatArray): FloatArray {
        val norm = sqrt(dot(vector, vector)).toFloat()
        return FloatArray(vector.size) { vector[it] / (norm + 1e-9f) }
    }

    private fun mean(labels: IntArray, pieces: List<Piece>, label: Int): FloatArray {
        val sum = FloatArray(pieces.first().embedding.size)
        var count = 0
        labels.forEachIndexed { i, l ->
            if (l != label) return@forEachIndexed
            val e = pieces[i].embedding
            for (k in sum.indices) sum[k] += e[k]
            count++
        }
        for (k in sum.indices) sum[k] /= count
        return sum
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.toDouble()
    }
}
