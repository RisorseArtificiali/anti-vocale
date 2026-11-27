package com.antivocale.app.transcription.diarization

import kotlin.math.sqrt

/**
 * TASK-1060.3: the parameters of the windowed diarization pass. The
 * [windowSec] range, [overlapSec], [pieceSec] and [threshold] are the
 * TASK-1060.2 spike's recommendation (zero merges on all six window x
 * composite cells at threshold 0.8; the first merge appears a full 0.1 step
 * higher) and [absorbMaxDistance] is measured on the same composites; the
 * KDoc of each other knob says where it comes from. TASK-1060.7: the
 * orchestrator derives [windowSec] and [maxPieces] from the device's memory
 * budget through [forRetentionBudget].
 */
data class WindowedDiarizationParams(
    val windowSec: Double = MAX_WINDOW_SEC,
    val overlapSec: Double = 30.0,
    val pieceSec: Double = 3.0,
    val threshold: Float = SpeakerDiarizer.AUTO_THRESHOLD,
    /**
     * A cluster with less talk than the floor [absorbFloorFor] derives is
     * absorbed or dropped. This is the floor's cap (TASK-1061).
     */
    val absorbFloorSec: Double = 30.0,
    /**
     * TASK-1060.7: the floor scales with the file's total piece talk, so a
     * short two-person note does not lose a real speaker to the 30 s cap.
     * UNMEASURED: no spike number stands behind 0.02; the TASK-1060.8
     * real-audio ground truth measures it.
     */
    val absorbFloorFraction: Double = 0.02,
    /**
     * TASK-1060.7: the floor never drops below this, so complete-linkage
     * fragments of one voice (3 to 6 s on a short run) still face the
     * [absorbMaxDistance] gate instead of becoming speakers of their own,
     * while a real 10 s speaker in a short note survives. A real speaker
     * with less than 10 s of talk in a short note is dropped: its cues stay
     * unlabeled, never put on another speaker. That is the deliberate
     * split-over-merge trade (an unlabeled cue beats a phantom or wrong
     * speaker). UNMEASURED: no spike number stands behind 10 s; the
     * TASK-1060.8 real-audio ground truth decides the value. A value above
     * [absorbFloorSec] is legal: the cap wins.
     */
    val absorbFloorMinSec: Double = 10.0,
    /**
     * TASK-1060.7: a sub-floor cluster joins its nearest big cluster only
     * within this cosine distance of the two normalized centroids;
     * otherwise its pieces are dropped and carry no speaker. On the
     * TASK-1060.2 VoxPopuli composites with a 30 s floor, ungated
     * absorption put 33 of 71 small clusters on the wrong speaker; at 0.45
     * only 2 of 24 accepted were wrong, agreement rose from
     * 0.9928/0.9934/0.9921 to 0.996/0.9971/0.9954 for 0.5 pt of coverage,
     * merges stayed 0.
     */
    val absorbMaxDistance: Double = 0.45,
    /**
     * Bound on the rows of the clustering's condensed distance matrix
     * (n(n-1)/2 floats, about 50 MB at the 5000 cap). [forRetentionBudget]
     * derives it from the memory budget, 500..5000. Above it, adjacent
     * pieces of one segment are merged pairwise (embeddings averaged); when
     * that cannot reach it (alternating short turns), a seeded random
     * subsample of exactly [maxPieces] pieces is clustered and every other
     * piece joins its nearest cluster centroid; pieces [threshold] or more
     * from every sampled centroid seed far clusters of their own, which
     * after reassignment can also hold rows near a sampled speaker (see
     * PieceClustering.clusterBounded).
     */
    val maxPieces: Int = MAX_PIECES,
) {
    init {
        // At least a second of stride, so neither the planner nor the
        // streaming driver can stall on a window that never advances.
        require(overlapSec >= 0 && windowSec - overlapSec >= 1.0) {
            "the window must exceed the overlap by at least 1 s"
        }
        require(pieceSec >= 1.0) { "pieces under 1 s would all be dropped as too short" }
        require(maxPieces >= 2) { "maxPieces must allow a clustering" }
        require(absorbMaxDistance in 0.0..2.0) { "cosine distance lies in 0..2" }
        require(absorbFloorFraction in 0.0..1.0) { "the floor fraction lies in 0..1" }
        require(absorbFloorMinSec >= 0) { "the floor's minimum cannot be negative" }
    }

    /** The talk floor for a file whose pieces total [talkSec]. */
    fun absorbFloorFor(talkSec: Double): Double =
        minOf(absorbFloorSec, maxOf(absorbFloorMinSec, absorbFloorFraction * talkSec))

    companion object {
        const val MAX_PIECES = 5000
        const val MIN_PIECES = 500
        const val MAX_WINDOW_SEC = 600.0
        const val MIN_WINDOW_SEC = 300.0

        /**
         * TASK-1060.7: bounds from the raw retention budget
         * (AudioDurationPolicy.retentionBudgetBytes). The piece cap keeps
         * the condensed float matrix (about 2 n^2 bytes) within half the
         * budget and the window's float buffer gets a quarter of it, but the
         * [MIN_PIECES] and [MIN_WINDOW_SEC] floors override a tiny budget so
         * the pass still clusters at a usable resolution. The buffer itself
         * never exceeds one window; two transients briefly hold an old and
         * a new array: growing the buffer (doubling while the decode length
         * is unknown or undercounted, straight to the window past half of
         * it) holds at most 1.5 windows, or under 2 when the first buffer
         * was sized to an undercounted estimate above half a window, and
         * the final partial window's exact-size copy under 2 windows. The
         * window clamps to 5..10 min (the TASK-1060.2 spike measured 5-min
         * and 10-min windows equivalent at threshold 0.8).
         */
        fun forRetentionBudget(budgetBytes: Long, sampleRate: Int): WindowedDiarizationParams {
            require(sampleRate > 0) { "the sample rate must be positive" }
            val budget = budgetBytes.coerceAtLeast(0L)
            val windowBytesPerSec = sampleRate * Float.SIZE_BYTES.toLong()
            return WindowedDiarizationParams(
                windowSec = (budget / (4 * windowBytesPerSec)).toDouble()
                    .coerceIn(MIN_WINDOW_SEC, MAX_WINDOW_SEC),
                maxPieces = sqrt(budget / 4.0).toInt().coerceIn(MIN_PIECES, MAX_PIECES),
            )
        }
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
    val stats: WindowedDiarizationStats,
)

/**
 * TASK-1060.7: what the global clustering did, so a bad device result can
 * be diagnosed from the log. Seconds are piece talk time. [rawClusters]
 * counts complete linkage's clusters (of the subsample past maxPieces), so
 * a high value is over-segmentation; [farClusters] counts the consolidated
 * clusters seeded by unsampled rows far from every sampled one that still
 * hold a row after reassignment, so a nonzero value is a voice or noise the
 * subsample missed. Absorption sees both kinds.
 */
data class WindowedDiarizationStats(
    val piecesIn: Int,
    val piecesAfterCoarsen: Int,
    val rawClusters: Int,
    val farClusters: Int,
    val absorbedClusters: Int,
    val absorbedSec: Double,
    val droppedClusters: Int,
    val droppedSec: Double,
    /** Pieces whose embedding was non-finite or unnormalizable, dropped before clustering. */
    val unusableEmbeddings: Int = 0,
)

/**
 * TASK-1060.7: [clusterBounded]'s per-row labels, plus how many came from
 * complete linkage and how many are consolidated far clusters.
 */
class BoundedClusters(val labels: IntArray, val linkageClusters: Int, val farClusters: Int)

/**
 * TASK-1060.7: [labels] holds each piece's cluster after absorption, null
 * for a piece whose small cluster was too far from every big one.
 */
class Absorption(
    val labels: List<Int?>,
    val absorbedClusters: Int,
    val absorbedSec: Double,
    val droppedClusters: Int,
    val droppedSec: Double,
    /** Pieces whose embedding was non-finite or unnormalizable, dropped before clustering. */
    val unusableEmbeddings: Int = 0,
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

    /** Fixed, so one input always yields one subsample and one labeling. */
    private const val SUBSAMPLE_SEED = 1060L

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
     * TASK-1061 absorption, gated by TASK-1060.7: a cluster whose total
     * piece time is under [floorSec] joins the large cluster whose
     * normalized centroid is closest by cosine to its own, but only within
     * [maxDistance]; otherwise its pieces get no speaker (an unlabeled cue
     * is honest, a wrong speaker is not). When no cluster reaches the
     * floor, every label is kept.
     */
    fun absorbSmall(labels: IntArray, pieces: List<Piece>, floorSec: Double, maxDistance: Double): Absorption {
        val talk = LinkedHashMap<Int, Double>()
        val sums = HashMap<Int, FloatArray>()
        labels.forEachIndexed { i, label ->
            talk[label] = (talk[label] ?: 0.0) + pieces[i].span.durationSec
            val sum = sums.getOrPut(label) { FloatArray(pieces[i].embedding.size) }
            val e = pieces[i].embedding
            for (k in sum.indices) sum[k] += e[k]
        }
        val big = talk.filterValues { it >= floorSec }.keys.toList()
        if (big.isEmpty()) return Absorption(labels.toList(), 0, 0.0, 0, 0.0)
        val centroids = big.map { normalized(sums.getValue(it)) }
        val target = HashMap<Int, Int?>()
        var absorbedSec = 0.0
        var droppedSec = 0.0
        for ((label, sec) in talk) {
            if (sec >= floorSec) continue
            val own = normalized(sums.getValue(label))
            val nearest = centroids.indices.maxBy { dot(centroids[it], own) }
            val accepted = 1.0 - dot(centroids[nearest], own) <= maxDistance
            target[label] = if (accepted) big[nearest] else null
            if (accepted) absorbedSec += sec else droppedSec += sec
        }
        val absorbed = target.values.count { it != null }
        return Absorption(
            labels = labels.map { if (it in target) target[it] else it },
            absorbedClusters = absorbed,
            absorbedSec = absorbedSec,
            droppedClusters = target.size - absorbed,
            droppedSec = droppedSec,
        )
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
     * [completeLinkage] over at most [maxPieces] rows. Past the cap, which
     * only happens when [coarsen] cannot reach it (about 1200 pieces an
     * hour; at the 5000 cap rarely under the 4 h ceiling, but a low-RAM
     * budget derives a cap down to 500, where hour-long alternating-turn
     * audio takes this path routinely), a seeded random subsample of exactly
     * [maxPieces] rows is clustered (an evenly spaced one phase-locks to
     * alternating turns and can miss a voice entirely) and the unsampled
     * rows are labeled in three steps:
     *
     * 1. Seeding: an unsampled row [threshold] or more from every sampled
     *    cluster's normalized centroid is a far row; a leader pass in index
     *    order puts it in the nearest far cluster within [threshold], else
     *    opens a new one.
     * 2. Consolidation: the closest two far clusters whose centroids are
     *    within [threshold] merge, until no pair is. This reduces, but does
     *    not remove, the seeding order's influence: halves of one voice
     *    whose centroids drift [threshold] apart stay split. Sampled
     *    clusters never merge here.
     * 3. Reassignment: every unsampled row takes the nearest centroid among
     *    the far clusters and the sampled clusters within [threshold] of it,
     *    so a missed voice's row near a sampled speaker still goes to its
     *    own far cluster when that is closer. A far cluster can only win
     *    over a within-threshold sampled one by being closer still, so no
     *    gate is needed on the far side.
     *
     * This approximates complete linkage over all rows with centroid
     * distances: one voice the subsample missed comes out as one cluster
     * that [absorbSmall] gates like any other, but a far cluster is held
     * together by its centroid, not by every pairwise distance. Cost, with U
     * unsampled rows, K sampled and F far clusters: O(U * (K + F) * dim)
     * for seeding and reassignment, O(F^3 * dim) worst case for
     * consolidation. [link] is the clustering, a seam so a test can observe
     * the rows it receives.
     */
    internal fun clusterBounded(
        embeddings: List<FloatArray>,
        threshold: Float,
        maxPieces: Int,
        link: (List<FloatArray>, Float) -> IntArray = ::completeLinkage,
    ): BoundedClusters {
        val n = embeddings.size
        if (n <= maxPieces) return link(embeddings, threshold).let { BoundedClusters(it, distinctCount(it), 0) }
        val random = java.util.Random(SUBSAMPLE_SEED)
        val order = IntArray(n) { it }
        for (i in 0 until maxPieces) {
            val j = i + random.nextInt(n - i)
            order[i] = order[j].also { order[j] = order[i] }
        }
        val sampled = order.copyOf(maxPieces).apply { sort() }
        val sampleLabels = link(sampled.map { embeddings[it] }, threshold)
        val sums = LinkedHashMap<Int, FloatArray>()
        sampled.forEachIndexed { j, row ->
            val sum = sums.getOrPut(sampleLabels[j]) { FloatArray(embeddings[row].size) }
            for (k in sum.indices) sum[k] += embeddings[row][k]
        }
        val clusterIds = sums.keys.toIntArray()
        val centroids = sums.values.map(::normalized)
        val labels = IntArray(n) { -1 }
        sampled.forEachIndexed { j, row -> labels[row] = sampleLabels[j] }
        val unsampled = (0 until n).filter { labels[it] == -1 }
        val row = FloatArray(embeddings[0].size)

        val farSums = ArrayList<FloatArray>()
        val farCentroids = ArrayList<FloatArray>()
        for (i in unsampled) {
            normalizeInto(embeddings[i], row)
            if (centroids.any { 1.0 - dot(it, row) < threshold }) continue
            val far = farCentroids.indices.maxByOrNull { dot(farCentroids[it], row) }
                ?.takeIf { 1.0 - dot(farCentroids[it], row) < threshold }
            if (far == null) {
                farSums += embeddings[i].copyOf()
                farCentroids += row.copyOf()
            } else {
                val sum = farSums[far]
                for (k in sum.indices) sum[k] += embeddings[i][k]
                normalizeInto(sum, farCentroids[far])
            }
        }

        while (true) {
            var bestSim = Double.NEGATIVE_INFINITY
            var keep = -1
            var gone = -1
            for (a in farCentroids.indices) for (b in a + 1 until farCentroids.size) {
                val sim = dot(farCentroids[a], farCentroids[b])
                if (sim > bestSim) {
                    bestSim = sim
                    keep = a
                    gone = b
                }
            }
            if (keep < 0 || 1.0 - bestSim >= threshold) break
            val sum = farSums[keep]
            for (k in sum.indices) sum[k] += farSums[gone][k]
            normalizeInto(sum, farCentroids[keep])
            farSums.removeAt(gone)
            farCentroids.removeAt(gone)
        }

        val farOf = IntArray(n) { -1 }
        for (i in unsampled) {
            normalizeInto(embeddings[i], row)
            var best = -1
            var bestSim = Double.NEGATIVE_INFINITY
            for (c in centroids.indices) {
                val sim = dot(centroids[c], row)
                if (1.0 - sim < threshold && sim > bestSim) {
                    best = c
                    bestSim = sim
                }
            }
            for (f in farCentroids.indices) {
                val sim = dot(farCentroids[f], row)
                if (sim > bestSim) {
                    best = centroids.size + f
                    bestSim = sim
                }
            }
            // A row no sampled cluster takes was far at seeding, so far
            // clusters exist; non-finite rows never get here (dropped where
            // pieces are built), so this only fails on a broken invariant.
            check(best >= 0)
            if (best < centroids.size) labels[i] = clusterIds[best] else farOf[i] = best - centroids.size
        }
        val firstFarLabel = (sampleLabels.maxOrNull() ?: -1) + 1
        val farLabel = IntArray(farCentroids.size) { -1 }
        var farClusters = 0
        for (i in unsampled) {
            val f = farOf[i]
            if (f < 0) continue
            if (farLabel[f] < 0) farLabel[f] = firstFarLabel + farClusters++
            labels[i] = farLabel[f]
        }
        return BoundedClusters(labels, sums.size, farClusters)
    }

    /**
     * Global clustering, absorption and assembly over every piece of the
     * file, plus each final speaker's centroid keyed by its dense id. A
     * dropped piece (see [absorbSmall]) leaves no segment and no centroid.
     */
    fun diarize(pieces: List<Piece>, params: WindowedDiarizationParams): WindowedDiarization {
        val bounded = coarsen(pieces, params.maxPieces)
        val clusters = clusterBounded(bounded.map { it.embedding }, params.threshold, params.maxPieces)
        val absorption = absorbSmall(
            clusters.labels, bounded,
            floorSec = params.absorbFloorFor(bounded.sumOf { it.span.durationSec }),
            maxDistance = params.absorbMaxDistance)
        val kept = ArrayList<Piece>(bounded.size)
        val keptLabels = ArrayList<Int>(bounded.size)
        absorption.labels.forEachIndexed { i, label ->
            if (label != null) {
                kept += bounded[i]
                keptLabels += label
            }
        }
        val labels = keptLabels.toIntArray()
        val raw = assembleRaw(kept, labels)
        // denseSegments owns the numbering; the centroid keys read its
        // raw->dense map instead of re-deriving it inline.
        val dense = SpeakerLabeler.denseSegments(raw)
        val denseIdByRaw = raw.map { it.speaker }.zip(dense.map { it.speaker }).toMap()
        val sums = HashMap<Int, FloatArray>()
        kept.forEachIndexed { i, piece ->
            val sum = sums.getOrPut(denseIdByRaw.getValue(labels[i])) { FloatArray(piece.embedding.size) }
            for (k in sum.indices) sum[k] += piece.embedding[k]
        }
        return WindowedDiarization(
            dense,
            sums.mapValues { (_, sum) -> normalized(sum) },
            WindowedDiarizationStats(
                piecesIn = pieces.size,
                piecesAfterCoarsen = bounded.size,
                rawClusters = clusters.linkageClusters,
                farClusters = clusters.farClusters,
                absorbedClusters = absorption.absorbedClusters,
                absorbedSec = absorption.absorbedSec,
                droppedClusters = absorption.droppedClusters,
                droppedSec = absorption.droppedSec,
            ),
        )
    }

    private fun distinctCount(labels: IntArray): Int {
        val sorted = labels.sortedArray()
        return sorted.indices.count { it == 0 || sorted[it] != sorted[it - 1] }
    }

    fun normalized(vector: FloatArray): FloatArray = FloatArray(vector.size).also { normalizeInto(vector, it) }

    private fun normalizeInto(vector: FloatArray, out: FloatArray) {
        val norm = sqrt(dot(vector, vector)).toFloat() + 1e-9f
        for (i in vector.indices) out[i] = vector[i] / norm
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.toDouble()
    }
}
