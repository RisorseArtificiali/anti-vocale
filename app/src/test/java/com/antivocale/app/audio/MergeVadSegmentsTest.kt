package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * GH #50 review finding: a single raw VAD segment longer than the merge limit
 * used to pass through whole (up to 600s of unbroken speech), bypassing the
 * model's per-segment cap. It must be split at the limit like any other chunk.
 */
class MergeVadSegmentsTest {

    @Test
    fun `merges adjacent segments under the limit`() {
        val merged = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(10) { 1f }, FloatArray(10) { 2f }),
            maxMergeSamples = 30,
        ).first
        assertEquals(1, merged.size)
        assertEquals(20, merged[0].size)
    }

    @Test
    fun `oversized single segment is split at the limit`() {
        val big = FloatArray(70) { 1f }
        val merged = AudioPreprocessor().mergeVadSegmentGroups(listOf(big), maxMergeSamples = 30).first

        assertEquals(3, merged.size)
        assertEquals(listOf(30, 30, 10), merged.map { it.size })
        // Content preserved in order
        assertEquals(big.toList(), merged.flatMap { it.toList() })
    }

    @Test
    fun `oversized segment among normal ones is split independently`() {
        val merged = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(5) { 1f }, FloatArray(50) { 2f }, FloatArray(5) { 3f }),
            maxMergeSamples = 20,
        ).first
        // 5+50: 50 alone exceeds 20, so the 5 stays alone (5+50=55 > 20);
        // 50 splits into 20+20+10; the trailing 5 cannot merge backwards into a split piece.
        assertEquals(listOf(5, 20, 20, 10, 5), merged.map { it.size })
    }
}
