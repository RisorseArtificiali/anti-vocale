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
        ).merged
        assertEquals(1, merged.size)
        assertEquals(20, merged[0].size)
    }

    @Test
    fun `oversized single segment is split at the limit`() {
        val big = FloatArray(70) { 1f }
        val merged = AudioPreprocessor().mergeVadSegmentGroups(listOf(big), maxMergeSamples = 30).merged

        assertEquals(3, merged.size)
        assertEquals(listOf(30, 30, 10), merged.map { it.size })
        // Content preserved in order
        assertEquals(big.toList(), merged.flatMap { it.toList() })
    }

    @Test
    fun `TASK-851 a merged group anchors every constituent segment start`() {
        // Two 10-sample segments at 10 samples per ms-equivalent (rate 10 ->
        // 1000 samples/s): the SECOND segment's buffer start is 1000ms in,
        // but its ORIGINAL start is 5000ms (3000ms of stripped silence
        // between the two original ranges). The anchors must restore that.
        val grouping = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(10) { 1f }, FloatArray(10) { 2f }),
            maxMergeSamples = 30,
            rangesMs = listOf(0L to 1000L, 5000L to 6000L),
            sampleRate = 10,
        )
        assertEquals(
            listOf(0L to 0L, 1000L to 5000L),
            grouping.anchorsMs.single())
        assertEquals(listOf(0L to 6000L), grouping.rangesMs)
    }

    @Test
    fun `TASK-851 three merged segments accumulate every stripped gap`() {
        // The case a two-segment test cannot pin: the THIRD anchor's buffer
        // offset must include BOTH earlier segments' samples (an
        // accumulate-only-the-previous-segment bug passes the two-segment
        // test and fails here).
        val grouping = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(10) { 1f }, FloatArray(10) { 2f }, FloatArray(10) { 3f }),
            maxMergeSamples = 40,
            rangesMs = listOf(0L to 1_000L, 5_000L to 6_000L, 9_000L to 10_000L),
            sampleRate = 10,
        )
        assertEquals(
            listOf(0L to 0L, 1_000L to 5_000L, 2_000L to 9_000L),
            grouping.anchorsMs.single())
    }

    @Test
    fun `TASK-851 unmerged chunks carry no anchors (flat shift exact)`() {
        val grouping = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(70) { 1f }),
            maxMergeSamples = 30,
            rangesMs = listOf(0L to 7000L),
            sampleRate = 10,
        )
        // Split pieces: no anchors.
        assertEquals(listOf(emptyList<Pair<Long, Long>>(), emptyList(), emptyList()),
            grouping.anchorsMs)
    }

    @Test
    fun `oversized segment among normal ones is split independently`() {
        val merged = AudioPreprocessor().mergeVadSegmentGroups(
            listOf(FloatArray(5) { 1f }, FloatArray(50) { 2f }, FloatArray(5) { 3f }),
            maxMergeSamples = 20,
        ).merged
        // 5+50: 50 alone exceeds 20, so the 5 stays alone (5+50=55 > 20);
        // 50 splits into 20+20+10; the trailing 5 cannot merge backwards into a split piece.
        assertEquals(listOf(5, 20, 20, 10, 5), merged.map { it.size })
    }
}
