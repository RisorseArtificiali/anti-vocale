package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-575 / GH #106: the measured per-model footprint that replaces the
 * disk-size estimate in the load pre-flight. Contracts pinned: required is
 * the delta plus headroom; a warm no-op sample never creates or strengthens
 * a record; a size change resets it; the max decays per merge so one noisy
 * sample does not raise the bar forever; the codec round-trips and the key
 * parser is the inverse of the builder.
 */
class MeasuredModelMemoryTest {

    private fun record(delta: Long = 1_500L * 1024 * 1024) = MeasuredModelMemory.Record(
        maxLoadDeltaBytes = delta,
        modelSizeBytes = 2_700L * 1024 * 1024,
        runs = 2,
        tsMs = 1_700_000_000_000,
    )

    @Test
    fun `required is the measured delta plus headroom, not the disk size`() {
        // The #63 shape: disk size 2.7GB made the estimate demand 3GB; the
        // measured resident cost is 1.5GB, so the bar must be ~1.8GB.
        val headroom = 300L * 1024 * 1024
        assertEquals(
            1_800L * 1024 * 1024,
            MeasuredModelMemory.requiredBytes(record(delta = 1_500L * 1024 * 1024), headroom),
        )
    }

    @Test
    fun `a warm no-op sample never creates a record`() {
        // Review F1: after a benchmark warms the backend singleton, a load
        // through configureBackend is a no-op with delta ~0; a record built
        // from it would collapse the bar to headroom-only.
        assertNull(MeasuredModelMemory.merge(existing = null, loadDeltaBytes = 0, modelSizeBytes = 100, nowMs = 10))
        assertNull(MeasuredModelMemory.merge(existing = null, loadDeltaBytes = -50, modelSizeBytes = 100, nowMs = 10))
    }

    @Test
    fun `a non-positive sample against an existing record keeps the delta and counts the run`() {
        val first = MeasuredModelMemory.merge(null, 1_000, 9_000, 10)!!
        val noisy = MeasuredModelMemory.merge(first, -200, 9_000, 20)!!
        assertEquals(1_000, noisy.maxLoadDeltaBytes)
        assertEquals(2, noisy.runs)
    }

    @Test
    fun `a model-size change resets the record`() {
        // Review F2: a re-import at the same path is a different model.
        val first = MeasuredModelMemory.merge(null, 1_000, 9_000, 10)!!
        val reset = MeasuredModelMemory.merge(first, 400, 9_500, 20)!!
        assertEquals(400, reset.maxLoadDeltaBytes)
        assertEquals(1, reset.runs)
    }

    @Test
    fun `the retained max decays per merge so one noisy sample ages out`() {
        // Review F4: another process allocating during the load window
        // inflates one delta sample; each later true sample decays the
        // retained max by 10% before taking the new max.
        val noisy = MeasuredModelMemory.merge(null, 2_000, 9_000, 10)!!
        var r = noisy
        repeat(20) { i: Int ->
            r = MeasuredModelMemory.merge(r, 1_000, 9_000, 20L + i)!!
        }
        assertTrue("decayed back to the true cost: ${r.maxLoadDeltaBytes}", r.maxLoadDeltaBytes <= 1_000)
        // A fresh larger sample still raises the bar immediately.
        val spike = MeasuredModelMemory.merge(r, 1_800, 9_000, 99)!!
        assertEquals(1_800, spike.maxLoadDeltaBytes)
    }

    @Test
    fun `codec round-trips, corrupt json yields an empty map, missing fields decode as zeros`() {
        val records = mapOf(
            "b@cpu@4@/p/one" to record(),
            "b@nnapi@4@/p/one" to record(delta = 700L * 1024 * 1024),
        )
        val decoded = MeasuredModelMemory.decode(MeasuredModelMemory.encode(records))
        assertEquals(records, decoded)
        assertTrue(MeasuredModelMemory.decode("{not json").isEmpty())
        assertTrue(MeasuredModelMemory.decode(null).isEmpty())
        assertTrue(MeasuredModelMemory.decode("").isEmpty())
        assertEquals(0L, MeasuredModelMemory.decode("""{"k": {}}""")["k"]?.maxLoadDeltaBytes)
    }

    @Test
    fun `pathOfKey is the inverse of the documented key shape`() {
        assertEquals("/data/models/parakeet-v3", MeasuredModelMemory.pathOfKey("parakeet@cpu@4@/data/models/parakeet-v3"))
        assertEquals(null, MeasuredModelMemory.pathOfKey("parakeet@cpu"))
    }
}
