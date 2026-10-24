package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-872: the VibeVoice window contract is 22+4 frames at 7.5 Hz latent
 * rate = 3.4667s. The slicer cuts arbitrary input into consecutive windows
 * of exactly that duration, zero-padding the tail.
 */
class VibeVoiceWindowSlicerTest {

    @Test
    fun `the window duration matches the model contract`() {
        assertEquals(3.4667, VibeVoiceWindowSlicer.WINDOW_SECONDS, 0.001)
    }

    @Test
    fun `empty input yields no windows`() {
        assertTrue(VibeVoiceWindowSlicer.sliceToWavWindows(FloatArray(0), 16000).isEmpty())
    }

    @Test
    fun `invalid sample rate yields no windows`() {
        assertTrue(VibeVoiceWindowSlicer.sliceToWavWindows(FloatArray(100), 0).isEmpty())
    }

    @Test
    fun `a short clip yields one zero-padded window`() {
        // 1s at 16kHz: less than one window
        val samples = FloatArray(16000) { it.toFloat() / 16000f }
        val windows = VibeVoiceWindowSlicer.sliceToWavWindows(samples, 16000)
        assertEquals(1, windows.size)
        // The WAV header is 44 bytes; the data is 3.4667s * 16000 * 2 bytes = ~110934
        val expectedDataBytes = (VibeVoiceWindowSlicer.WINDOW_SECONDS * 16000 * 2).toInt()
        val wavSize = windows[0].size
        // Allow the WAV header (44 bytes standard) + potential extra header bytes
        assertTrue("window WAV should be around $expectedDataBytes + header, was $wavSize",
            wavSize >= expectedDataBytes && wavSize <= expectedDataBytes + 128)
    }

    @Test
    fun `a two-window clip yields two windows`() {
        // 5s at 16kHz: more than one window (3.47s), less than two full
        val samples = FloatArray(5 * 16000)
        val windows = VibeVoiceWindowSlicer.sliceToWavWindows(samples, 16000)
        assertEquals(2, windows.size)
        // The second window covers 5 - 3.4667 = 1.53s of content, padded to 3.47s
        val secondContentBytes = ((5.0 - VibeVoiceWindowSlicer.WINDOW_SECONDS) * 16000 * 2).toInt()
        val secondTotal = (VibeVoiceWindowSlicer.WINDOW_SECONDS * 16000 * 2).toInt()
        assertTrue("second window should be the padded full size",
            windows[1].size >= secondTotal && windows[1].size <= secondTotal + 128)
    }

    @Test
    fun `an exact multiple yields no trailing padded window`() {
        // Exactly 2 windows, computed the same way the slicer does (ceil)
        val windowSize = kotlin.math.ceil(VibeVoiceWindowSlicer.WINDOW_SECONDS * 16000).toInt()
        val samples = FloatArray(windowSize * 2)
        val windows = VibeVoiceWindowSlicer.sliceToWavWindows(samples, 16000)
        assertEquals(2, windows.size)
    }

    @Test
    fun `the routing detects vibevoice by file name`() {
        assertTrue(LlmTranscriptionBackend.isVibeVoiceModel("/path/to/VibeVoice-ASR-Streaming-1.5B_int8.litertlm"))
        assertTrue(LlmTranscriptionBackend.isVibeVoiceModel("/path/to/vibevoice-any.litertlm"))
        assertTrue(!LlmTranscriptionBackend.isVibeVoiceModel("/path/to/gemma-4-E2B-it.litertlm"))
        assertTrue(!LlmTranscriptionBackend.isVibeVoiceModel(null))
        assertTrue(!LlmTranscriptionBackend.isVibeVoiceModel(""))
    }
}
