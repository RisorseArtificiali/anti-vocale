package com.antivocale.app.transcription.diarization

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1060.3: the pure core of the windowed diarization pass. The
 * clustering port is pinned against piece-clustering-oracle.json: 240 real
 * titanet-small piece embeddings with the partitions sherpa-onnx 2.0.2's
 * own FastClustering produced at three thresholds.
 */
class PieceClusteringTest {

    private val params = WindowedDiarizationParams()
    private val rate = 16000

    private fun span(start: Double, end: Double) = TimeSpan(start, end)

    private fun piece(start: Double, end: Double, vararg embedding: Float) =
        Piece(span(start, end), PieceClustering.normalized(embedding))

    /** Same co-membership for every pair: label values themselves are opaque. */
    private fun assertSamePartition(expected: IntArray, actual: IntArray, message: String) {
        assertEquals(message, expected.size, actual.size)
        for (i in expected.indices) for (j in i + 1 until expected.size) {
            assertEquals(
                "$message: pair ($i, $j)",
                expected[i] == expected[j], actual[i] == actual[j])
        }
    }

    @Test
    fun `complete linkage reproduces sherpa FastClustering on the oracle at every threshold`() {
        val text = javaClass.classLoader!!
            .getResourceAsStream("diarization/piece-clustering-oracle.json")!!
            .bufferedReader().use { it.readText() }
        val oracle = JSONObject(text)
        val pieces = oracle.getJSONArray("pieces")
        val embeddings = List(pieces.length()) { i ->
            val emb = pieces.getJSONObject(i).getJSONArray("emb")
            FloatArray(emb.length()) { emb.getDouble(it).toFloat() }
        }
        val cases = oracle.getJSONArray("cases")
        assertEquals(3, cases.length())
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val threshold = case.getDouble("threshold").toFloat()
            val json = case.getJSONArray("labels")
            val expected = IntArray(json.length()) { json.getInt(it) }
            val actual = PieceClustering.completeLinkage(embeddings, threshold)
            assertSamePartition(expected, actual, "threshold $threshold")
            assertEquals(expected.toSet().size, actual.toSet().size)
        }
    }

    @Test
    fun `merges at or above the threshold stay apart and those below join`() {
        // Pairwise cosine distances: a-b 0, a-c 1, b-c 1 (orthogonal).
        val a = floatArrayOf(1f, 0f)
        val c = floatArrayOf(0f, 1f)
        assertSamePartition(intArrayOf(0, 0, 1),
            PieceClustering.completeLinkage(listOf(a, a, c), 1.0f), "distance 1 at threshold 1")
        assertSamePartition(intArrayOf(0, 0, 0),
            PieceClustering.completeLinkage(listOf(a, a, c), 1.01f), "distance 1 below threshold 1.01")
    }

    @Test
    fun `complete linkage handles empty and single inputs`() {
        assertEquals(0, PieceClustering.completeLinkage(emptyList(), 0.8f).size)
        assertArrayEquals(intArrayOf(0),
            PieceClustering.completeLinkage(listOf(floatArrayOf(1f, 2f)), 0.8f))
    }

    @Test
    fun `a segment is cut into pieces from its start`() {
        assertEquals(listOf(span(10.0, 13.0), span(13.0, 16.0)),
            PieceClustering.cutPieces(span(10.0, 16.0), 3.0))
        assertEquals(listOf(span(0.0, 3.0), span(3.0, 4.2)),
            PieceClustering.cutPieces(span(0.0, 4.2), 3.0))
    }

    @Test
    fun `a leftover under one second joins the previous piece`() {
        assertEquals(listOf(span(0.0, 3.0), span(3.0, 6.9)),
            PieceClustering.cutPieces(span(0.0, 6.9), 3.0))
        assertEquals(listOf(span(0.0, 3.5)), PieceClustering.cutPieces(span(0.0, 3.5), 3.0))
    }

    @Test
    fun `a short segment is one piece and one under half a second is dropped`() {
        assertEquals(listOf(span(2.0, 2.8)), PieceClustering.cutPieces(span(2.0, 2.8), 3.0))
        assertEquals(emptyList<TimeSpan>(), PieceClustering.cutPieces(span(2.0, 2.4), 3.0))
    }

    @Test
    fun `windows step by window minus overlap and own their core regions`() {
        val total = 25L * 60 * rate
        val plans = PieceClustering.planWindows(total, rate, params)
        assertEquals(3, plans.size)
        assertEquals(listOf(0L, 570L * rate, 1140L * rate), plans.map { it.startSample })
        assertEquals(listOf(600L * rate, 1170L * rate, total), plans.map { it.endSample })
        assertEquals(0.0, plans[0].coreLoSec, 1e-9)
        assertEquals(585.0, plans[0].coreHiSec, 1e-9)
        assertEquals(585.0, plans[1].coreLoSec, 1e-9)
        assertEquals(1155.0, plans[1].coreHiSec, 1e-9)
        assertEquals(1155.0, plans[2].coreLoSec, 1e-9)
        assertEquals(1500.0, plans[2].coreHiSec, 1e-9)
    }

    @Test
    fun `audio no longer than one window is a single window owning everything`() {
        val short = PieceClustering.planWindows(90L * rate, rate, params)
        assertEquals(listOf(WindowPlan(0, 90L * rate, 0.0, 90.0)), short)
        val exact = PieceClustering.planWindows(600L * rate, rate, params)
        assertEquals(listOf(WindowPlan(0, 600L * rate, 0.0, 600.0)), exact)
        assertEquals(emptyList<WindowPlan>(), PieceClustering.planWindows(0, rate, params))
    }

    @Test
    fun `a window ending exactly on the audio end is the last`() {
        val plans = PieceClustering.planWindows(1170L * rate, rate, params)
        assertEquals(2, plans.size)
        assertEquals(1170.0, plans[1].coreHiSec, 1e-9)
    }

    @Test
    fun `a segment crossing a core boundary is split between the two windows`() {
        val plans = PieceClustering.planWindows(25L * 60 * rate, rate, params)
        // The first core ends at 585 (600 - overlap/2); the core is half-open.
        assertEquals(
            listOf(span(580.0, 585.0), span(585.0, 589.0), null),
            plans.map { it.clip(span(580.0, 589.0)) })
        assertEquals(listOf(span(570.0, 584.0), null, null), plans.map { it.clip(span(570.0, 584.0)) })
    }

    @Test
    fun `a cluster below the talk floor joins the nearest large cluster`() {
        val pieces = listOf(
            piece(0.0, 20.0, 1f, 0f, 0f),
            piece(20.0, 40.0, 0f, 1f, 0f),
            piece(40.0, 43.0, 0.9f, 0.1f, 0f),
            piece(43.0, 46.0, 0.1f, 0.9f, 0.2f),
        )
        val absorbed = PieceClustering.absorbSmall(
            intArrayOf(0, 1, 2, 3), pieces, floorSec = 15.0, maxDistance = 0.45)
        assertEquals(listOf(0, 1, 0, 1), absorbed.labels)
        assertEquals(2, absorbed.absorbedClusters)
        assertEquals(6.0, absorbed.absorbedSec, 1e-9)
        assertEquals(0, absorbed.droppedClusters)
    }

    @Test
    fun `labels stay unchanged when no cluster reaches the floor`() {
        val pieces = listOf(piece(0.0, 3.0, 1f, 0f), piece(3.0, 6.0, 0f, 1f))
        assertEquals(listOf(0, 1),
            PieceClustering.absorbSmall(intArrayOf(0, 1), pieces, floorSec = 30.0, maxDistance = 0.45).labels)
    }

    @Test
    fun `a small cluster far from every big one is dropped instead of absorbed`() {
        // Two big speakers on x and y; a short z voice is orthogonal to both
        // (cosine distance 1), a short x-leaning one sits within 0.45 of x.
        val pieces = listOf(
            piece(0.0, 40.0, 1f, 0f, 0f),
            piece(40.0, 80.0, 0f, 1f, 0f),
            piece(80.0, 83.0, 0f, 0f, 1f),
            piece(83.0, 86.0, 1f, 0.3f, 0f),
        )
        val absorbed = PieceClustering.absorbSmall(
            intArrayOf(0, 1, 2, 3), pieces, floorSec = 30.0, maxDistance = 0.45)
        assertEquals(listOf(0, 1, null, 0), absorbed.labels)
        assertEquals(1, absorbed.absorbedClusters)
        assertEquals(1, absorbed.droppedClusters)
        assertEquals(3.0, absorbed.droppedSec, 1e-9)
    }

    @Test
    fun `dropped pieces leave no segment and no centroid while the rest stay intact`() {
        // 203 s of talk puts the floor at its 10 s minimum: the 3 s z voice is small and far.
        val pieces = listOf(
            piece(0.0, 100.0, 1f, 0f, 0f),
            piece(100.0, 200.0, 0f, 1f, 0f),
            piece(200.0, 203.0, 0f, 0f, 1f),
        )
        val result = PieceClustering.diarize(pieces, params.copy(threshold = 0.2f))

        assertEquals(
            listOf(DiarizedSegment(0f, 100f, 0, null), DiarizedSegment(100f, 200f, 1, null)),
            result.segments)
        assertEquals(setOf(0, 1), result.centroids.keys)
        // A cue over the dropped instants overlaps no segment: it stays unlabeled.
        val cues = listOf(com.antivocale.app.transcription.TimedSegment(200_500L, 202_500L, "ciao"))
        assertEquals(listOf<Int?>(null), SpeakerLabeler.label(cues, result.segments))
    }

    @Test
    fun `the relative floor keeps a short real speaker and absorbs a fragment of its voice`() {
        // 124 s of talk: 2 percent is 2.48 s, so the 10 s minimum sets the
        // floor, well under the 30 s cap. The 10 s y speaker survives; a 4 s
        // y-leaning fragment clusters apart at 0.05 but sits within the gate
        // of y, so it joins y instead of becoming a third speaker.
        val pieces = (0 until 10).map { piece(it * 11.0, it * 11.0 + 11, 1f, 0f) } +
            (0 until 5).map { piece(200 + it * 2.0, 202 + it * 2.0, 0f, 1f) } +
            piece(300.0, 304.0, 0.5f, 1f)
        val result = PieceClustering.diarize(pieces, params.copy(threshold = 0.05f))

        assertEquals(2, result.centroids.size)
        assertEquals(
            listOf(DiarizedSegment(200f, 210f, 1, null), DiarizedSegment(300f, 304f, 1, null)),
            result.segments.takeLast(2))
        assertEquals(1, result.stats.absorbedClusters)
        assertEquals(4.0, result.stats.absorbedSec, 1e-9)
        assertEquals(0, result.stats.droppedClusters)
    }

    @Test
    fun `the floor is the fraction of talk between the minimum and the cap`() {
        assertEquals(10.0, params.absorbFloorFor(120.0), 1e-9)
        assertEquals(20.0, params.absorbFloorFor(1000.0), 1e-9)
        assertEquals(30.0, params.absorbFloorFor(10_000.0), 1e-9)
        // A minimum above the cap: the cap wins.
        assertEquals(5.0, WindowedDiarizationParams(absorbFloorSec = 5.0).absorbFloorFor(120.0), 1e-9)
    }

    @Test
    fun `clustering never receives more rows than maxPieces`() {
        val embeddings = (0 until 11).map { if (it % 2 == 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val seen = ArrayList<Int>()
        val labels = PieceClustering.clusterBounded(embeddings, 0.5f, maxPieces = 4) { rows, threshold ->
            seen += rows.size
            PieceClustering.completeLinkage(rows, threshold)
        }
        assertEquals(listOf(4), seen)
        assertEquals(11, labels.size)
    }

    @Test
    fun `alternating turns coarsen cannot shrink are subsampled and each piece keeps its voice`() {
        // Six separate 5 s turns alternate x and y, so no contiguous pair
        // merges. maxPieces 4 clusters a subsample of four rows, which holds
        // both voices (only three rows are x); the other two rows join their
        // nearest centroid.
        val pieces = (0 until 6).map {
            if (it % 2 == 0) piece(it * 10.0, it * 10.0 + 5, 1f, 0.05f) else piece(it * 10.0, it * 10.0 + 5, 0.05f, 1f)
        }
        val result = PieceClustering.diarize(pieces, params.copy(maxPieces = 4))

        assertEquals(6, result.stats.piecesAfterCoarsen)
        assertEquals(2, result.stats.rawClusters)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), result.segments.map { it.speaker })
    }

    @Test
    fun `a subsample of half the rows keeps both voices of strictly alternating turns`() {
        // n = 2 x maxPieces: an evenly spaced subsample would take only the
        // even rows, so one voice would never reach the clustering.
        val embeddings = (0 until 100).map { if (it % 2 == 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val labels = PieceClustering.clusterBounded(embeddings, 0.5f, maxPieces = 50)

        val even = labels.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val odd = labels.filterIndexed { i, _ -> i % 2 == 1 }.toSet()
        assertEquals(1, even.size)
        assertEquals(1, odd.size)
        assertTrue("both voices survive", even != odd)
    }

    @Test
    fun `an unsampled row far from every cluster is labeled neither A nor B`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val c = floatArrayOf(0f, 0f, 1f)
        val n = 30
        val ab = (0 until n).map { if (it % 2 == 0) a.copyOf() else b.copyOf() }
        val sampled = HashSet<Int>()
        PieceClustering.clusterBounded(ab, 0.5f, maxPieces = 10) { rows, threshold ->
            rows.forEach { row -> sampled += ab.indexOfFirst { it === row } }
            PieceClustering.completeLinkage(rows, threshold)
        }
        // The subsample depends only on the row count, so a third voice
        // placed in two unsampled rows never reaches the clustering.
        val third = (0 until n).filter { it !in sampled }.take(2)
        val embeddings = ab.mapIndexed { i, row -> if (i in third) c else row }
        val labels = PieceClustering.clusterBounded(embeddings, 0.5f, maxPieces = 10)

        val aLabel = labels[(0 until n).first { it in sampled && it % 2 == 0 }]
        val bLabel = labels[(0 until n).first { it in sampled && it % 2 == 1 }]
        for (i in third) {
            assertTrue("row $i of the third voice is not A or B", labels[i] != aLabel && labels[i] != bLabel)
        }
    }

    @Test
    fun `stats count pieces, clusters, absorbed and dropped talk`() {
        // Six contiguous x pieces coarsen pairwise into three to fit 5.
        // 185 s of talk puts the floor at its 10 s minimum: the 3 s x-leaning cluster
        // is close (absorbed), the 2 s z cluster is far (dropped).
        val pieces = (0 until 6).map { piece(it * 30.0, it * 30.0 + 30, 1f, 0f, 0f) } + listOf(
            piece(200.0, 203.0, 1f, 0.5f, 0f),
            piece(210.0, 212.0, 0f, 0f, 1f),
        )
        val stats = PieceClustering.diarize(
            pieces, params.copy(threshold = 0.05f, maxPieces = 5)).stats

        assertEquals(
            WindowedDiarizationStats(
                piecesIn = 8, piecesAfterCoarsen = 5, rawClusters = 3,
                absorbedClusters = 1, absorbedSec = 3.0,
                droppedClusters = 1, droppedSec = 2.0),
            stats)
    }

    @Test
    fun `budget-derived bounds hit their floors on a small budget and caps on a large one`() {
        val small = WindowedDiarizationParams.forRetentionBudget(512L * 1024, rate)
        assertEquals(500, small.maxPieces)
        assertEquals(300.0, small.windowSec, 1e-9)

        val large = WindowedDiarizationParams.forRetentionBudget(1024L * 1024 * 1024, rate)
        assertEquals(5000, large.maxPieces)
        assertEquals(600.0, large.windowSec, 1e-9)

        // 64 MiB: sqrt(16 Mi) pieces, whose float matrix fits half the budget.
        val budget = 64L * 1024 * 1024
        val mid = WindowedDiarizationParams.forRetentionBudget(budget, rate)
        assertEquals(4096, mid.maxPieces)
        assertTrue(mid.maxPieces.toLong() * (mid.maxPieces - 1) / 2 * 4 <= budget / 2)
        // 100 MB: the window buffer (64000 B/s) gets a quarter of the budget.
        assertEquals(390.0, WindowedDiarizationParams.forRetentionBudget(100_000_000L, rate).windowSec, 1e-9)
    }

    @Test
    fun `assembly merges touching same-speaker pieces and densifies ids by first appearance`() {
        val pieces = listOf(
            piece(6.0, 9.0, 1f),
            piece(0.0, 3.0, 1f),
            piece(3.0, 6.0, 1f),
            piece(10.0, 13.0, 1f),
            piece(13.0, 15.0, 1f),
        )
        val segments = PieceClustering.assemble(pieces, intArrayOf(7, 4, 4, 4, 7))
        assertEquals(
            listOf(
                DiarizedSegment(0f, 6f, 0, null),
                DiarizedSegment(6f, 9f, 1, null),
                DiarizedSegment(10f, 13f, 0, null),
                DiarizedSegment(13f, 15f, 1, null),
            ),
            segments,
        )
    }

    @Test
    fun `same-speaker pieces separated by a gap stay separate`() {
        val pieces = listOf(piece(0.0, 3.0, 1f), piece(4.0, 7.0, 1f))
        assertEquals(2, PieceClustering.assemble(pieces, intArrayOf(0, 0)).size)
        assertTrue(PieceClustering.assemble(emptyList(), IntArray(0)).isEmpty())
    }

    @Test
    fun `touching pieces at non-representable boundaries rejoin into one turn`() {
        // A segment at 593.1 s: its Double cut points round DOWN as Float, so a
        // Float-rounded end compared with the next exact start split the turn.
        val cuts = PieceClustering.cutPieces(span(590.1, 602.1), 3.0)
        val pieces = cuts.map { Piece(it, PieceClustering.normalized(floatArrayOf(1f, 0f))) }
        val segments = PieceClustering.assemble(pieces, IntArray(pieces.size))
        assertEquals(1, segments.size)
        assertEquals(590.1f, segments.single().startSec)
        assertEquals(602.1f, segments.single().endSec)
    }

    @Test
    fun `an overlapping piece of another speaker does not break a turn`() {
        val pieces = listOf(
            piece(0.0, 3.0, 1f, 0f), piece(1.0, 2.0, 0f, 1f), piece(3.0, 6.0, 1f, 0f))
        val segments = PieceClustering.assemble(pieces, intArrayOf(0, 1, 0))
        assertEquals(
            listOf(DiarizedSegment(0f, 6f, 0, null), DiarizedSegment(1f, 2f, 1, null)),
            segments)
    }

    @Test
    fun `coarsening halves contiguous pieces until the count fits`() {
        // Two segments of four 3 s pieces each, with a gap between them.
        val pieces = (0 until 4).map { piece(it * 3.0, it * 3.0 + 3, 1f, 0f) } +
            (0 until 4).map { piece(20 + it * 3.0, 23 + it * 3.0, 0f, 1f) }
        val coarse = PieceClustering.coarsen(pieces, 4)
        assertEquals(4, coarse.size)
        assertEquals(listOf(span(0.0, 6.0), span(6.0, 12.0), span(20.0, 26.0), span(26.0, 32.0)),
            coarse.map { it.span })
        assertEquals(pieces, PieceClustering.coarsen(pieces, 8))
        // Nothing contiguous left to merge: returned as is, never looping.
        val isolated = listOf(piece(0.0, 3.0, 1f, 0f), piece(5.0, 8.0, 1f, 0f))
        assertEquals(isolated, PieceClustering.coarsen(isolated, 1))
    }

    @Test
    fun `parameters that would stall or drop every piece are rejected`() {
        for (bad in listOf(
            { WindowedDiarizationParams(windowSec = 30.0, overlapSec = 29.5) },
            { WindowedDiarizationParams(pieceSec = 0.4) },
        )) {
            assertTrue(runCatching { bad() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `diarize keys each speaker's centroid by its dense id`() {
        // Speaker B (y axis) speaks first, so it is dense id 0; its pieces
        // lean either side of the axis, so the centroid is their normalized sum.
        val pieces = listOf(
            piece(0.0, 3.0, 0.1f, 1f),
            piece(3.0, 6.0, -0.1f, 1f),
            piece(10.0, 13.0, 1f, 0f),
            piece(13.0, 16.0, 1f, 0f),
        )
        val result = PieceClustering.diarize(pieces, params.copy(absorbFloorSec = 1.0))

        assertEquals(listOf(0, 1), result.segments.map { it.speaker })
        assertEquals(setOf(0, 1), result.centroids.keys)
        assertArrayEquals(floatArrayOf(0f, 1f), result.centroids.getValue(0), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f), result.centroids.getValue(1), 1e-6f)
    }

    @Test
    fun `an absorbed speaker's pieces count toward the centroid of the speaker it joined`() {
        // The short x-leaning turn clusters alone at 0.2, is under the floor,
        // and joins the long x speaker (closer by cosine than y).
        val pieces = listOf(
            piece(0.0, 3.0, 1f, 0f),
            piece(3.0, 6.0, 1f, 0f),
            piece(6.0, 7.0, 1f, 0.8f),
            piece(10.0, 40.0, 0f, 1f),
        )
        val result = PieceClustering.diarize(pieces, params.copy(threshold = 0.2f, absorbFloorSec = 5.0))

        assertEquals(setOf(0, 1), result.centroids.keys)
        val x = result.centroids.getValue(0)
        assertTrue("the absorbed piece pulls the centroid off the axis", x[1] > 0f)
        assertEquals(1.0, kotlin.math.sqrt((x[0] * x[0] + x[1] * x[1]).toDouble()), 1e-5)
    }
}
