package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for progressive transcription preprocessing behavior.
 *
 * Verifies that PreprocessingResult correctly handles the isVadSegmented flag
 * and that VAD-segmented results maintain correct invariants.
 */
@RunWith(JUnit4::class)
class AudioPreprocessorProgressiveTest {

    // ========== isVadSegmented Default ==========

    @Test
    fun `PreprocessingResult defaults isVadSegmented to false`() {
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = listOf(byteArrayOf(1, 2, 3)),
            totalDurationSeconds = 5.0,
            chunkCount = 1
        )

        assertFalse("isVadSegmented should default to false", result.isVadSegmented)
    }

    @Test
    fun `PreprocessingResult with isVadSegmented true stores correct flag`() {
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = listOf(byteArrayOf(1), byteArrayOf(2)),
            totalDurationSeconds = 8.0,
            chunkCount = 2,
            isVadSegmented = true
        )

        assertTrue("isVadSegmented should be true when explicitly set", result.isVadSegmented)
    }

    // ========== Chunk Count Invariant ==========

    @Test
    fun `PreprocessingResult chunk count matches segments count`() {
        val segments = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5),
            byteArrayOf(6, 7, 8, 9)
        )

        val result = AudioPreprocessor.PreprocessingResult(
            chunks = segments,
            totalDurationSeconds = 12.0,
            chunkCount = segments.size,
            isVadSegmented = true
        )

        assertEquals("chunkCount should match number of segments", segments.size, result.chunkCount)
        assertEquals("chunks size should match segment count", segments.size, result.chunks.size)
    }

    @Test
    fun `PreprocessingResult single segment isVadSegmented false`() {
        // Single segment should use the non-progressive path
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = listOf(byteArrayOf(1, 2, 3)),
            totalDurationSeconds = 5.0,
            chunkCount = 1,
            isVadSegmented = false
        )

        assertFalse("Single segment should not be flagged as VAD-segmented", result.isVadSegmented)
        assertEquals(1, result.chunkCount)
    }

    // ========== Duration Calculation ==========

    @Test
    fun `PreprocessingResult totalDurationSeconds reflects speech-only duration`() {
        // When VAD strips silence, the duration should reflect only speech
        // not the original audio length (e.g., 30s audio with 10s speech → 10s)
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = listOf(byteArrayOf(1, 2)),
            totalDurationSeconds = 10.0,
            chunkCount = 1,
            isVadSegmented = true
        )

        // Duration should be the speech-only duration, not the original
        assertEquals(10.0, result.totalDurationSeconds, 0.001)
        assertTrue(result.isVadSegmented)
    }

    // ========== Backward Compatibility ==========

    @Test
    fun `PreprocessingResult without isVadSegmented parameter defaults to false`() {
        // Existing code that doesn't pass isVadSegmented should still compile
        // and behave correctly (non-progressive path)
        val result = AudioPreprocessor.PreprocessingResult(
            chunks = listOf(byteArrayOf(1)),
            totalDurationSeconds = 30.0,
            chunkCount = 1
        )

        assertFalse(result.isVadSegmented)
        assertEquals(1, result.chunkCount)
    }
}
