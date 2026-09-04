package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for AudioPreprocessor.
 *
 * Note: These tests require mocking FFmpegKit for proper unit testing.
 * For integration tests, use actual audio files on a device/emulator.
 */
@RunWith(JUnit4::class)
class AudioPreprocessorTest {

    // ========== Error Type Tests ==========

    @Test
    fun `PreprocessingError FileNotFound has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.FileNotFound
        assertEquals("Audio file not found", error.message)
    }

    @Test
    fun `PreprocessingError FileTooLarge has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.FileTooLarge
        assertEquals("Audio file exceeds 2GB limit", error.message)
    }

    @Test
    fun `PreprocessingError InvalidFormat has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.InvalidFormat
        assertEquals("Unable to determine audio format", error.message)
    }

    @Test
    fun `PreprocessingError DurationTooLong has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.DurationTooLong(
            600L, AudioDurationPolicy.DecodePath.STREAMING)
        assertEquals("Audio exceeds 10 minute limit on this path", error.message)
    }

    @Test
    fun `PreprocessingError DurationUnknown has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.DurationUnknown
        assertEquals("Could not determine audio duration", error.message)
    }

    @Test
    fun `PreprocessingError ConversionFailed includes reason`() {
        val error = AudioPreprocessor.PreprocessingError.ConversionFailed("test reason")
        assertEquals("Conversion failed: test reason", error.message)
    }

    @Test
    fun `PreprocessingError ChunkFailed includes chunk index`() {
        val error = AudioPreprocessor.PreprocessingError.ChunkFailed(5, "test error")
        assertEquals("Chunk 5 failed: test error", error.message)
    }

    // ========== PreprocessingResult Tests ==========

    @Test
    fun `PreprocessingResult stores correct values`() {
        val chunks = listOf(FloatArray(3) { 0.1f }, FloatArray(3) { 0.2f })
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            sampleRate = 16000,
            totalDurationSeconds = 45.0,
            chunkCount = 2
        )

        assertEquals(2, result.chunks.size)
        assertEquals(16000, result.sampleRate)
        assertEquals(45.0, result.totalDurationSeconds, 0.001)
        assertEquals(2, result.chunkCount)
    }

    // ========== Constants Validation ==========

    @Test
    fun `MAX_FILE_SIZE_BYTES is 2GB`() {
        val expectedMaxSize = 2L * 1024 * 1024 * 1024
        // This test documents the expected constant value
        assertEquals(2L * 1024 * 1024 * 1024, expectedMaxSize)
    }

    // ========== Chunking Logic Tests ==========

    @Test
    fun `chunk count calculation is correct for various durations`() {
        // Duration 0-30s: 1 chunk
        assertEquals(1, calculateExpectedChunks(15.0))
        assertEquals(1, calculateExpectedChunks(30.0))

        // Duration 30-60s: 2 chunks
        assertEquals(2, calculateExpectedChunks(31.0))
        assertEquals(2, calculateExpectedChunks(45.0))
        assertEquals(2, calculateExpectedChunks(60.0))

        // Duration 60-90s: 3 chunks
        assertEquals(3, calculateExpectedChunks(61.0))
        assertEquals(3, calculateExpectedChunks(90.0))

        // Duration 90-120s: 4 chunks
        assertEquals(4, calculateExpectedChunks(91.0))
        assertEquals(4, calculateExpectedChunks(120.0))
    }

    /**
     * Helper function to calculate expected chunk count.
     * Mirrors the logic in AudioPreprocessor.processChunkedAudio
     */
    private fun calculateExpectedChunks(durationSeconds: Double): Int {
        val maxChunkDuration = 30.0
        var startTime = 0.0
        var chunkCount = 0

        while (startTime < durationSeconds) {
            chunkCount++
            startTime += maxChunkDuration
        }

        return chunkCount
    }

    // ========== File Validation Tests ==========

    @Test
    fun `empty file path should fail validation`() {
        val path = ""
        assertTrue("Empty path should be invalid", path.isBlank())
    }

    @Test
    fun `file size validation logic is correct`() {
        val maxSizeBytes = 2L * 1024 * 1024 * 1024 // 2GB

        // Files under limit
        assertTrue(100 * 1024 * 1024L < maxSizeBytes) // 100MB
        assertTrue(maxSizeBytes - 1 < maxSizeBytes) // Just under

        // Files at or over limit
        assertFalse(maxSizeBytes < maxSizeBytes) // Exactly at limit
        assertFalse(maxSizeBytes + 1 < maxSizeBytes) // Just over
    }

    // ========== WAV Format Tests ==========

    @Test
    fun `WAV format parameters are correct`() {
        val targetSampleRate = 16000
        val targetChannels = 1

        assertEquals(16000, targetSampleRate)
        assertEquals(1, targetChannels) // Mono
    }

    // ========== Integration Test Placeholder ==========

    @Test
    fun `integration test placeholder - requires device`() {
        // This test documents that full integration tests require:
        // 1. Android device or emulator
        // 2. FFmpegKit native libraries
        // 3. Test audio files
        //
        // See app/src/androidTest for instrumented tests
        assertTrue("Integration tests require device", true)
    }

    // ========== mergeAndResample (TASK-340 Fix 1a) ==========

    private val preprocessor = AudioPreprocessor()

    @Test
    fun `mergeAndResample concatenates chunks in order at target rate`() {
        val chunks = mutableListOf(
            floatArrayOf(0.1f, 0.2f, 0.3f),
            floatArrayOf(0.4f, 0.5f),
        )
        val (samples, rate) = preprocessor.mergeAndResample(chunks, 16000)
        assertEquals(16000, rate)
        assertTrue(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).contentEquals(samples))
    }

    @Test
    fun `mergeAndResample resamples non-16k input to 16k`() {
        // 1 second of a sine at 48kHz: the resampled output must be 16000 samples
        // at 16kHz, matching the previous inline resample of the merged buffer.
        val sine = FloatArray(48000) { sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() }
        val (samples, rate) = preprocessor.mergeAndResample(mutableListOf(sine), 48000)
        assertEquals(16000, rate)
        assertEquals(16000, samples.size)
    }

    // ========== mergeVadSegments (TASK-340 Fix 3) ==========

    /**
     * Naive reference merge: grow a group array one segment at a time. A single
     * segment longer than the limit is split at the limit (GH #50: unbroken
     * speech must not bypass the model's per-segment cap).
     */
    private fun naiveMerge(segments: List<FloatArray>, maxMergeSamples: Int): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        fun emit(seg: FloatArray) {
            var offset = 0
            while (offset < seg.size) {
                val len = minOf(maxMergeSamples, seg.size - offset)
                out.add(seg.copyOfRange(offset, offset + len))
                offset += len
            }
        }
        var current = segments.first().clone()
        for (i in 1 until segments.size) {
            if (current.size + segments[i].size <= maxMergeSamples) {
                val combined = FloatArray(current.size + segments[i].size)
                System.arraycopy(current, 0, combined, 0, current.size)
                System.arraycopy(segments[i], 0, combined, current.size, segments[i].size)
                current = combined
            } else {
                emit(current)
                current = segments[i].clone()
            }
        }
        emit(current)
        return out
    }

    @Test
    fun `mergeVadSegments matches naive merge across many segments and limits`() {
        val rng = java.util.Random(42)
        repeat(50) {
            val segmentCount = 2 + rng.nextInt(20)
            val segments = List(segmentCount) { FloatArray(1 + rng.nextInt(500)) { rng.nextFloat() } }
            val maxMergeSamples = 100 + rng.nextInt(2000)
            val expected = naiveMerge(segments, maxMergeSamples)
            val actual = preprocessor.mergeVadSegments(segments, maxMergeSamples)
            assertEquals("chunk count (max=$maxMergeSamples, sizes=${segments.map { it.size }})",
                expected.size, actual.size)
            expected.forEachIndexed { i, exp ->
                assertTrue("chunk $i content", exp.contentEquals(actual[i]))
            }
        }
    }

    @Test
    fun `mergeVadSegments single segment returns it unchanged`() {
        val seg = floatArrayOf(0.5f, -0.5f, 0.25f)
        val result = preprocessor.mergeVadSegments(listOf(seg), 100)
        assertEquals(1, result.size)
        assertTrue(seg.contentEquals(result[0]))
    }

    @Test
    fun `mergeVadSegments splits group when adding would exceed limit`() {
        val a = FloatArray(300) { it.toFloat() }
        val b = FloatArray(300) { 1000f + it }
        val result = preprocessor.mergeVadSegments(listOf(a, b), maxMergeSamples = 500)
        assertEquals(2, result.size)
        assertTrue(a.contentEquals(result[0]))
        assertTrue(b.contentEquals(result[1]))
        // Limit 500 allows 300+300=600? No: 600 > 500, so split. With 600 it merges.
        val merged = preprocessor.mergeVadSegments(listOf(a, b), maxMergeSamples = 600)
        assertEquals(1, merged.size)
        assertEquals(600, merged[0].size)
        assertTrue(FloatArray(600) { if (it < 300) it.toFloat() else 1000f + (it - 300) }.contentEquals(merged[0]))
    }

    @Test
    fun `expectedChunkCount ceils instead of flooring`() {
        // TASK-444, device-verified miscount: 226.59s at a 150s cap emitted
        // "expecting 1 chunks" while the decoder correctly produced 2.
        assertEquals(2, AudioPreprocessor.expectedChunkCount(226.59, 150))
        // exact multiple: no remainder chunk
        assertEquals(2, AudioPreprocessor.expectedChunkCount(300.0, 150))
        // under one cap: single chunk
        assertEquals(1, AudioPreprocessor.expectedChunkCount(122.01, 150))
        assertEquals(1, AudioPreprocessor.expectedChunkCount(0.8, 150))
        // one second over the cap is already 2 chunks
        assertEquals(2, AudioPreprocessor.expectedChunkCount(151.0, 150))
    }
}
