package com.antivocale.app.transcription

import com.antivocale.app.util.WavUtils

/**
 * TASK-872: the VibeVoice streaming models take ONE fixed audio window per
 * conversation turn (22 content frames + 4 look-ahead frames at the 7.5 Hz
 * latent rate = 3.4667 seconds). This slicer cuts arbitrary-length input
 * into consecutive windows of that duration (the sample count is CEILed
 * so the 26th frame is never truncated by floor rounding); the last
 * window is zero-padded to the full size so the model sees a complete
 * frame set.
 *
 * The window duration is wall-clock: the input's sample rate is preserved
 * (the LiteRT-LM AudioBytes path decodes the WAV container and resamples
 * internally), so a 3.4667s window at 16kHz input is the same model input
 * as a 3.4667s window at 24kHz.
 */
object VibeVoiceWindowSlicer {

    /** 22 content frames + 4 look-ahead, all at the 7.5 Hz latent rate. */
    const val WINDOW_SECONDS = (22.0 + 4.0) / 7.5 // 3.4667s

    /**
     * Slices [samples] (at [sampleRate] Hz) into consecutive WAV-encoded
     * windows of [WINDOW_SECONDS] each. The last window is zero-padded.
     * Returns an empty list for empty or non-positive-rate input.
     */
    fun sliceToWavWindows(samples: FloatArray, sampleRate: Int): List<ByteArray> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        // ceil, not floor: at 16kHz the exact window is 55466.67 samples;
        // floor truncates the 26th frame by ~2/3 sample on EVERY window.
        val windowSize = kotlin.math.ceil(WINDOW_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val windows = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + windowSize, samples.size)
            val window = if (end - offset == windowSize) {
                samples.copyOfRange(offset, end)
            } else {
                // Zero-pad the tail so the model sees a complete frame set.
                FloatArray(windowSize).also {
                    samples.copyOfRange(offset, end).copyInto(it)
                }
            }
            windows.add(WavUtils.floatSamplesToWav(window, sampleRate))
            offset = end
        }
        return windows
    }
}
