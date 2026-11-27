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
        val absorbed = PieceClustering.absorbSmall(intArrayOf(0, 1, 2, 3), pieces, floorSec = 15.0)
        assertArrayEquals(intArrayOf(0, 1, 0, 1), absorbed)
    }

    @Test
    fun `labels stay unchanged when no cluster reaches the floor`() {
        val pieces = listOf(piece(0.0, 3.0, 1f, 0f), piece(3.0, 6.0, 0f, 1f))
        assertArrayEquals(intArrayOf(0, 1),
            PieceClustering.absorbSmall(intArrayOf(0, 1), pieces, floorSec = 30.0))
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
}
