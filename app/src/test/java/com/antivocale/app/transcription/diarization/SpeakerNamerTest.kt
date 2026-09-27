package com.antivocale.app.transcription.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-670 (GH #83): threshold behavior and the cluster-naming path. The
 * embedder is a fake (identity lookup by marker), so these tests pin the
 * DECISIONS, not the native extractor.
 */
class SpeakerNamerTest {

    private fun identity(name: String, vararg values: Float): SpeakerIdentity =
        SpeakerIdentity(id = name, name = name, embedding = values, sampleSeconds = 5f)

    private val alice = identity("Alice", 1f, 0f)
    private val bob = identity("Bob", 0f, 1f)

    // --- cosineSimilarity ---

    @Test
    fun `identical vectors score one and opposite vectors score minus one`() {
        assertEquals(1f, SpeakerNamer.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f))!!, 1e-6f)
        assertEquals(-1f, SpeakerNamer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f))!!, 1e-6f)
        assertEquals(0f, SpeakerNamer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 1e-6f)
    }

    @Test
    fun `a dimension mismatch or zero vector has no similarity, never a match`() {
        assertNull(SpeakerNamer.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertNull(SpeakerNamer.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)))
    }

    // --- bestMatch: match / no-match / borderline ---

    @Test
    fun `a similarity above the threshold matches`() {
        val query = floatArrayOf(0.99f, 0.14f) // cos ~ 0.99 with Alice
        assertEquals("Alice", SpeakerNamer.bestMatch(query, listOf(alice, bob))?.name)
    }

    @Test
    fun `a similarity below the threshold matches nobody`() {
        val query = floatArrayOf(1f, 1f).let { v -> // 45 degrees from both
            FloatArray(v.size) { i -> v[i] / kotlin.math.sqrt(2f) }
        }
        // cos(45deg) ~ 0.707 > 0.60 default; force a high threshold to pin
        // the no-match arm instead of relying on the default's value.
        assertNull(SpeakerNamer.bestMatch(query, listOf(alice, bob), threshold = 0.9f))
    }

    @Test
    fun `a first-listed identity wins an exact tie and the boundary stays inclusive`() {
        // Review R7 changed >= to > (ties keep the FIRST identity). The
        // boundary semantics: the bestSimilarity seed IS the threshold, so
        // a similarity exactly AT the threshold must still match (the > is
        // against the running BEST, not the floor). Construct both cases.
        val threshold = 0.6f
        val sine = kotlin.math.sqrt(1f - threshold * threshold)
        val query = floatArrayOf(threshold, sine) // unit vector, cos = 0.6 with (1,0)
        assertEquals("the boundary stays inclusive",
            "Alice", SpeakerNamer.bestMatch(query, listOf(alice), threshold)?.name)
        val bob = alice.copy(name = "Bob") // same embedding: an exact tie
        assertEquals("ties keep the FIRST-listed identity",
            "Alice", SpeakerNamer.bestMatch(query, listOf(alice, bob), threshold)?.name)
    }

    @Test
    fun `the best of several above-threshold identities wins`() {
        // Closer to Alice than to Bob.
        val query = floatArrayOf(0.95f, 0.31f)
        assertEquals("Alice", SpeakerNamer.bestMatch(query, listOf(bob, alice))?.name)
    }

    // --- nameClusters: the diarization-time path ---

    private fun seg(startSec: Float, endSec: Float, speaker: Int) =
        DiarizedSegment(startSec, endSec, speaker, null)

    @Test
    fun `a matched cluster is named and an unrecognized cluster stays out`() {
        val segments = listOf(
            seg(0f, 4f, 0), // an unrecognized voice
            seg(5f, 9f, 1), // Bob's span
        )
        val samples = FloatArray(16_000 * 10)
        samples[0] = 1f // marker: the first embed call is cluster 0's slice
        // Cluster 0 embeds at 45 degrees from both identities (cos ~0.707),
        // cluster 1 embeds as Bob exactly; at threshold 0.8 only Bob clears.
        val embed: (FloatArray, Int) -> FloatArray = { buffer, _ ->
            if (buffer.first() == 1f) {
                FloatArray(2) { 1f / kotlin.math.sqrt(2f) }
            } else {
                floatArrayOf(0f, 1f)
            }
        }
        val names = SpeakerNamer.nameClusters(
            segments, samples, 16_000, listOf(alice, bob), embed = embed, threshold = 0.8f)

        assertEquals(mapOf(1 to "Bob"), names)
    }

    @Test
    fun `the fake embedder keyed on content separates the clusters`() {
        val segments = listOf(seg(0f, 4f, 0), seg(5f, 9f, 1))
        // 10 s of samples; speaker 0 talks in 0..4 s, speaker 1 in 5..9 s.
        val samples = FloatArray(16_000 * 10)
        samples[0] = 1f // speaker 0's marker in their slice
        val embed: (FloatArray, Int) -> FloatArray = { buffer, _ ->
            if (buffer.first() == 1f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
        val names = SpeakerNamer.nameClusters(
            segments, samples, 16_000, listOf(alice, bob), embed = embed)

        assertEquals(mapOf(0 to "Alice", 1 to "Bob"), names)
    }

    @Test
    fun `a cluster under the speech floor is never embedded`() {
        val segments = listOf(seg(0f, 0.5f, 0)) // 0.5 s < MIN_CLUSTER_SECONDS
        var embedCalls = 0
        val embed: (FloatArray, Int) -> FloatArray = { _, _ -> embedCalls++; floatArrayOf(1f, 0f) }

        val names = SpeakerNamer.nameClusters(
            segments, FloatArray(16_000), 16_000, listOf(alice), embed = embed)

        assertTrue(names.isEmpty())
        assertEquals(0, embedCalls)
    }

    @Test
    fun `an extraction failure on one cluster leaves the others named`() {
        val segments = listOf(seg(0f, 4f, 0), seg(5f, 9f, 1))
        val embed: (FloatArray, Int) -> FloatArray = { buffer, _ ->
            if (buffer.first() == 1f) throw IllegalStateException("native boom")
            floatArrayOf(0f, 1f)
        }
        val samples = FloatArray(16_000 * 10)
        samples[0] = 1f

        val names = SpeakerNamer.nameClusters(
            segments, samples, 16_000, listOf(alice, bob), embed = embed)

        assertEquals(mapOf(1 to "Bob"), names)
    }

    @Test
    fun `no enrolled identities names nothing`() {
        val segments = listOf(seg(0f, 4f, 0))
        val names = SpeakerNamer.nameClusters(
            segments, FloatArray(16_000 * 5), 16_000, emptyList(),
            embed = { _, _ -> floatArrayOf(1f) })
        assertTrue(names.isEmpty())
    }

    @Test
    fun `a dimension-mismatched enrollment never matches`() {
        // The enrolled embedding lives in another model's space (dim 3 vs 2).
        val foreign = identity("Foreign", 0.5f, 0.5f, 0.7f)
        val segments = listOf(seg(0f, 4f, 0))
        val names = SpeakerNamer.nameClusters(
            segments, FloatArray(16_000 * 5), 16_000, listOf(foreign),
            embed = { _, _ -> floatArrayOf(1f, 0f) })
        assertTrue(names.isEmpty())
    }

    @Test
    fun `slices are clamped to the buffer's real extent`() {
        // Segments past the buffer's end must not throw or wrap around.
        val segments = listOf(seg(0f, 2f, 0), seg(100f, 200f, 0))
        val names = SpeakerNamer.nameClusters(
            segments, FloatArray(16_000 * 3), 16_000, listOf(alice),
            embed = { _, _ -> floatArrayOf(1f, 0f) })
        assertEquals(mapOf(0 to "Alice"), names)
    }
}
