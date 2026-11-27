package com.antivocale.app.transcription.diarization

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1060.3: the window driver with fake segmenter and embedder. The
 * synthetic audio encodes its speaker in the sample VALUE (1.0 for A, 2.0
 * for B, 0.0 silence), so the fake embedder can recover identity from the
 * exact samples the driver hands it: a wrong window offset or slice shows
 * up as a wrong speaker.
 */
class WindowedDiarizerTest {

    private val rate = WindowedDiarizer.SAMPLE_RATE

    /** Small windows keep the arrays light: 60 s windows, 10 s overlap. */
    private val params = WindowedDiarizationParams(
        windowSec = 60.0, overlapSec = 10.0, pieceSec = 3.0, threshold = 0.8f, absorbFloorSec = 5.0,
        absorbFloorMinSec = 0.0)

    /** Speaker turns as (startSec, endSec, value) over [totalSec] of audio. */
    private fun audio(totalSec: Int, vararg turns: Triple<Int, Int, Float>): FloatArray {
        val samples = FloatArray(totalSec * rate)
        for ((start, end, value) in turns) samples.fill(value, start * rate, end * rate)
        return samples
    }

    private fun chunked(samples: FloatArray, chunkSec: Double): List<FloatArray> {
        val size = (chunkSec * rate).toInt()
        return (samples.indices step size).map {
            samples.copyOfRange(it, minOf(samples.size, it + size))
        }
    }

    /** Non-silent runs of the window, in seconds from the window's start. */
    private val segmentBySpeech: (FloatArray) -> List<TimeSpan> = { samples ->
        val spans = ArrayList<TimeSpan>()
        var start = -1
        for (i in 0..samples.size) {
            val speech = i < samples.size && samples[i] != 0f
            val boundary = i < samples.size && start >= 0 && samples[i] != samples[start]
            if (start >= 0 && (!speech || boundary)) {
                spans += TimeSpan(start.toDouble() / rate, i.toDouble() / rate)
                start = -1
            }
            if (speech && start < 0) start = i
        }
        spans
    }

    /** One-hot embedding of the slice's majority value: A and B are orthogonal. */
    private val embedByValue: (FloatArray, List<IntRange>) -> List<FloatArray?> = { samples, ranges ->
        ranges.map { range ->
            val values = range.map { samples[it] }.filter { it != 0f }
            if (values.isEmpty()) null else {
                val a = values.count { it == 1f }
                if (a * 2 >= values.size) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            }
        }
    }

    @Test
    fun `speakers keep one global id across windows`() = runTest {
        // A and B alternate across 150 s: three 60 s windows (0-60, 50-110, 100-150).
        val samples = audio(150,
            Triple(0, 20, 1f), Triple(21, 45, 2f), Triple(46, 70, 1f),
            Triple(71, 100, 2f), Triple(101, 130, 1f), Triple(131, 150, 2f))
        val progress = ArrayList<Pair<Int, Int?>>()
        val diarizer = WindowedDiarizer(params, segmentBySpeech, embedByValue)

        val segments = diarizer.diarize(
            flowOf(*chunked(samples, 7.3).toTypedArray()), samples.size.toLong()) { done, total ->
            progress += done to total
        }.segments

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(
            listOf(
                DiarizedSegment(0f, 20f, 0, null), DiarizedSegment(21f, 45f, 1, null),
                DiarizedSegment(46f, 70f, 0, null), DiarizedSegment(71f, 100f, 1, null),
                DiarizedSegment(101f, 130f, 0, null), DiarizedSegment(131f, 150f, 1, null),
            ),
            segments,
        )
    }

    @Test
    fun `a segment in an overlap is kept once`() = runTest {
        // 51-58 sits in the first overlap (50-60): both windows see it whole,
        // each keeps only its core half (51-55, 55-58) and assembly rejoins them.
        val samples = audio(100, Triple(10, 40, 1f), Triple(51, 58, 2f), Triple(70, 95, 2f))
        val windows = ArrayList<Int>()
        val counting: (FloatArray) -> List<TimeSpan> = { windows += it.size; segmentBySpeech(it) }
        val segments = WindowedDiarizer(params, counting, embedByValue)
            .diarize(flowOf(samples)).segments

        assertEquals(listOf(60 * rate, 50 * rate), windows)
        assertEquals(1, segments.count { it.startSec < 58f && it.endSec > 51f })
        assertEquals(DiarizedSegment(51f, 58f, 1, null), segments[1])
        assertEquals(setOf(0, 1), segments.map { it.speaker }.toSet())
    }

    @Test
    fun `the buffer grows to the window whatever the decode length claims`() = runTest {
        // An unknown, an undercounted and an exact length must cut the same
        // windows from the same audio: the buffer grows instead of truncating.
        val samples = audio(150, Triple(0, 40, 1f), Triple(41, 100, 2f), Triple(101, 150, 1f))
        suspend fun cut(totalSamples: Long?): Pair<List<Int>, List<DiarizedSegment>> {
            val windows = ArrayList<Int>()
            val counting: (FloatArray) -> List<TimeSpan> = { windows += it.size; segmentBySpeech(it) }
            val segments = WindowedDiarizer(params, counting, embedByValue)
                .diarize(flowOf(*chunked(samples, 7.3).toTypedArray()), totalSamples).segments
            return windows to segments
        }
        val exact = cut(samples.size.toLong())
        assertEquals(listOf(60 * rate, 60 * rate, 50 * rate), exact.first)
        assertEquals(exact, cut(null))
        assertEquals(exact, cut(10L * rate))
    }

    @Test
    fun `an unknown length on a window larger than the initial buffer cuts the known-length windows and segments`() = runTest {
        // A 150 s window starts an unknown-length buffer at 60 s, below it,
        // so the buffer must grow (60, then straight to 150). The test
        // guards window and segment equivalence only; buffer sizes are not
        // observable here.
        val wide = params.copy(windowSec = 150.0)
        val samples = audio(330,
            Triple(0, 70, 1f), Triple(71, 160, 2f), Triple(161, 250, 1f), Triple(251, 330, 2f))
        suspend fun cut(totalSamples: Long?): Pair<List<Int>, List<DiarizedSegment>> {
            val windows = ArrayList<Int>()
            val counting: (FloatArray) -> List<TimeSpan> = { windows += it.size; segmentBySpeech(it) }
            val segments = WindowedDiarizer(wide, counting, embedByValue)
                .diarize(flowOf(*chunked(samples, 7.3).toTypedArray()), totalSamples).segments
            return windows to segments
        }
        val known = cut(samples.size.toLong())
        assertEquals(listOf(150 * rate, 150 * rate, 50 * rate), known.first)
        assertEquals(setOf(0, 1), known.second.map { it.speaker }.toSet())
        assertEquals(known, cut(null))
    }

    @Test
    fun `pieces the embedder rejects are dropped and empty audio yields nothing`() = runTest {
        val samples = audio(30, Triple(0, 9, 1f))
        val rejectAll: (FloatArray, List<IntRange>) -> List<FloatArray?> = { _, r -> r.map { null } }
        assertTrue(WindowedDiarizer(params, segmentBySpeech, rejectAll)
            .diarize(flowOf(samples)).segments.isEmpty())
        assertTrue(WindowedDiarizer(params, segmentBySpeech, embedByValue)
            .diarize(flowOf()).segments.isEmpty())
    }

    @Test
    fun `audio exactly one window long is one last window`() = runTest {
        val samples = audio(60, Triple(5, 55, 1f))
        val progress = ArrayList<Pair<Int, Int?>>()
        val segments = WindowedDiarizer(params, segmentBySpeech, embedByValue)
            .diarize(flowOf(samples)) { done, total -> progress += done to total }.segments
        assertEquals(listOf(1 to null), progress)
        assertEquals(listOf(DiarizedSegment(5f, 55f, 0, null)), segments)
    }

    @Test
    fun `the centroids name the same global speakers across windows`() = runTest {
        val samples = audio(150, Triple(0, 40, 1f), Triple(41, 100, 2f), Triple(101, 150, 1f))
        val diarizer = WindowedDiarizer(params, segmentBySpeech, embedByValue)
        val result = diarizer.diarize(flowOf(samples), samples.size.toLong())

        assertEquals(3, diarizer.windowCount(samples.size.toLong()))
        assertArrayEquals(floatArrayOf(1f, 0f), result.centroids.getValue(0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f), result.centroids.getValue(1), 1e-6f)
    }
}
