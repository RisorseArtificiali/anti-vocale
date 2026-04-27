package com.antivocale.app.data

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionCalibratorTest {

    private val calibrator = TranscriptionCalibrator(mockk(relaxed = true))

    // --- buildKey ---

    @Test
    fun `buildKey extracts last path component`() {
        assertEquals("whisper__whisper-turbo", calibrator.buildKey("whisper", "/data/models/whisper-turbo"))
    }

    @Test
    fun `buildKey handles trailing slash`() {
        assertEquals("sherpa-onnx__distil", calibrator.buildKey("sherpa-onnx", "/data/models/distil/"))
    }

    @Test
    fun `buildKey handles simple filename`() {
        assertEquals("llm__model.bin", calibrator.buildKey("llm", "model.bin"))
    }

    @Test
    fun `buildKey handles root path`() {
        assertEquals("qwen3-asr__", calibrator.buildKey("qwen3-asr", "/"))
    }

    @Test
    fun `buildKey handles empty path`() {
        assertEquals("backend__", calibrator.buildKey("backend", ""))
    }

    // --- CalibrationProfile.confidence ---

    @Test
    fun `confidence is NONE with zero samples`() {
        assertEquals(
            TranscriptionCalibrator.CalibrationProfile.Confidence.NONE,
            profile(sampleCount = 0).confidence
        )
    }

    @Test
    fun `confidence is NONE with one sample`() {
        assertEquals(
            TranscriptionCalibrator.CalibrationProfile.Confidence.NONE,
            profile(sampleCount = 1).confidence
        )
    }

    @Test
    fun `confidence is LOW with two samples`() {
        assertEquals(
            TranscriptionCalibrator.CalibrationProfile.Confidence.LOW,
            profile(sampleCount = 2).confidence
        )
    }

    @Test
    fun `confidence is HIGH with three samples`() {
        assertEquals(
            TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH,
            profile(sampleCount = 3).confidence
        )
    }

    // --- CalibrationProfile.hasEstimate ---

    @Test
    fun `hasEstimate is false with zero samples`() {
        assertFalse(profile(sampleCount = 0).hasEstimate)
    }

    @Test
    fun `hasEstimate is false with one sample`() {
        assertFalse(profile(sampleCount = 1).hasEstimate)
    }

    @Test
    fun `hasEstimate is true with two samples`() {
        assertTrue(profile(sampleCount = 2).hasEstimate)
    }

    // --- CalibrationProfile data class defaults ---

    @Test
    fun `CalibrationProfile has correct default values`() {
        val p = profile(sampleCount = 1)
        assertEquals(0L, p.totalAudioSeconds)
        assertEquals(0L, p.totalProcessingMs)
        assertEquals(Float.MAX_VALUE, p.bestMsPerSec, 0f)
        assertEquals(0L, p.lastTimestamp)
    }

    @Test
    fun `CalibrationProfile stores all fields`() {
        val p = TranscriptionCalibrator.CalibrationProfile(
            modelId = "whisper__turbo",
            displayName = "Whisper Turbo",
            msPerSecondOfAudio = 42.5f,
            sampleCount = 7,
            totalAudioSeconds = 120L,
            totalProcessingMs = 5100L,
            bestMsPerSec = 38.0f,
            lastTimestamp = 1700000000L
        )
        assertEquals("whisper__turbo", p.modelId)
        assertEquals("Whisper Turbo", p.displayName)
        assertEquals(42.5f, p.msPerSecondOfAudio, 0.001f)
        assertEquals(7, p.sampleCount)
        assertEquals(120L, p.totalAudioSeconds)
        assertEquals(5100L, p.totalProcessingMs)
        assertEquals(38.0f, p.bestMsPerSec, 0.001f)
        assertEquals(1700000000L, p.lastTimestamp)
    }

    private fun profile(
        sampleCount: Int,
        msPerSec: Float = 100f
    ) = TranscriptionCalibrator.CalibrationProfile(
        modelId = "test",
        displayName = "Test",
        msPerSecondOfAudio = msPerSec,
        sampleCount = sampleCount
    )
}
