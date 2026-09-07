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

    // --- backendIdOf (the key decode the share-shortcut recency source uses) ---

    @Test
    fun `backendIdOf decodes a normal key and round-trips through buildKey`() {
        val key = calibrator.buildKey("whisper", "/data/models/whisper-turbo")
        assertEquals("whisper", calibrator.backendIdOf(key))
    }

    @Test
    fun `backendIdOf splits on the first separator when the dirName contains one`() {
        // dirNames may contain "__": only the FIRST segment is the backend id.
        assertEquals("sherpa-onnx", calibrator.backendIdOf("sherpa-onnx__distil__it"))
    }

    @Test
    fun `backendIdOf keeps an external hex record id intact`() {
        // External backend ids are "external:" + a hex record id; the hex never
        // contains the separator, so the decode is lossless even over a
        // separator-carrying dirName.
        val externalId = "external:9f2c1ab3e0d54f6a8b7c2d1e"
        val key = calibrator.buildKey(externalId, "/storage/models/ctc__small")
        assertEquals(externalId, calibrator.backendIdOf(key))
    }

    @Test
    fun `backendIdOf returns a separator-less legacy key unchanged`() {
        // A key persisted without any separator decodes to itself: the whole
        // string was the backend id.
        assertEquals("whisper", calibrator.backendIdOf("whisper"))
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
