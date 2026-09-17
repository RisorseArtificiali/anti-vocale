package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * GH #92: the range-aware merge must keep cue ranges aligned with the chunk
 * grouping, so output chunk i's timestamps are output range i.
 */
class MergeVadSegmentGroupsTest {

    private val sampleRate = 1000 // 1 sample = 1 ms, keeps the arithmetic obvious

    @Test
    fun `grouped segments span first start to last end`() {
        val segments = listOf(FloatArray(10), FloatArray(10))
        val ranges = listOf(0L to 10L, 20L to 30L)

        val (merged, mergedRanges) = AudioPreprocessor().mergeVadSegmentGroups(
            segments, maxMergeSamples = 30, rangesMs = ranges, sampleRate = sampleRate)

        assertEquals(1, merged.size)
        assertEquals(listOf(0L to 30L), mergedRanges)
    }

    @Test
    fun `split oversized segment slices its range proportionally`() {
        val big = FloatArray(70)
        val ranges = listOf(100L to 170L)

        val (merged, mergedRanges) = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(big), maxMergeSamples = 30, rangesMs = ranges, sampleRate = sampleRate)

        assertEquals(3, merged.size)
        assertEquals(listOf(30, 30, 10), merged.map { it.size })
        assertEquals(listOf(100L to 130L, 130L to 160L, 160L to 170L), mergedRanges)
    }

    @Test
    fun `ungrouped segments keep one range each`() {
        val segments = listOf(FloatArray(10), FloatArray(10))
        val ranges = listOf(0L to 10L, 20L to 30L)

        val (merged, mergedRanges) = AudioPreprocessor().mergeVadSegmentGroups(
            segments, maxMergeSamples = 10, rangesMs = ranges, sampleRate = sampleRate)

        assertEquals(listOf(10, 10), merged.map { it.size })
        assertEquals(ranges, mergedRanges)
    }

    @Test
    fun `empty ranges stay empty`() {
        val (merged, mergedRanges) = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(10)), maxMergeSamples = 30, rangesMs = emptyList(), sampleRate = sampleRate)

        assertEquals(1, merged.size)
        assertEquals(emptyList<Pair<Long, Long>>(), mergedRanges)
    }
}
