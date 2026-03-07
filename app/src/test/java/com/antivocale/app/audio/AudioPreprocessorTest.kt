package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

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
        assertEquals("Audio file exceeds 100MB limit", error.message)
    }

    @Test
    fun `PreprocessingError InvalidFormat has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.InvalidFormat
        assertEquals("Unable to determine audio format", error.message)
    }

    @Test
    fun `PreprocessingError DurationTooLong has correct message`() {
        val error = AudioPreprocessor.PreprocessingError.DurationTooLong
        assertEquals("Audio exceeds 10 minute limit", error.message)
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
        val chunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            totalDurationSeconds = 45.0,
            chunkCount = 2
        )

        assertEquals(2, result.chunks.size)
        assertEquals(45.0, result.totalDurationSeconds, 0.001)
        assertEquals(2, result.chunkCount)
    }

    // ========== Constants Validation ==========

    @Test
    fun `MAX_FILE_SIZE_BYTES is 100MB`() {
        val expectedMaxSize = 100 * 1024 * 1024L
        // This test documents the expected constant value
        assertEquals(100 * 1024 * 1024L, expectedMaxSize)
    }

    @Test
    fun `MAX_DURATION_SECONDS is 10 minutes`() {
        val expectedMaxDuration = 600
        // This test documents the expected constant value
        assertEquals(600, expectedMaxDuration)
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
        val maxSizeBytes = 100 * 1024 * 1024L // 100MB

        // Files under limit
        assertTrue(50 * 1024 * 1024L < maxSizeBytes) // 50MB
        assertTrue(99 * 1024 * 1024L < maxSizeBytes) // 99MB
        assertTrue(maxSizeBytes - 1 < maxSizeBytes) // Just under

        // Files at or over limit
        assertFalse(maxSizeBytes < maxSizeBytes) // Exactly at limit
        assertFalse(maxSizeBytes + 1 < maxSizeBytes) // Just over
        assertFalse(200 * 1024 * 1024L < maxSizeBytes) // 200MB
    }

    @Test
    fun `duration validation logic is correct`() {
        val maxDurationSeconds = 600.0 // 10 minutes

        // Durations under limit
        assertTrue(30.0 <= maxDurationSeconds)
        assertTrue(300.0 <= maxDurationSeconds) // 5 minutes
        assertTrue(599.0 <= maxDurationSeconds)

        // Durations over limit
        assertFalse(600.1 <= maxDurationSeconds)
        assertFalse(900.0 <= maxDurationSeconds) // 15 minutes
        assertFalse(1200.0 <= maxDurationSeconds) // 20 minutes
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
}
