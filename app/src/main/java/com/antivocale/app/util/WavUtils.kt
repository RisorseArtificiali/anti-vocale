package com.antivocale.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility functions for WAV audio data processing.
 */
object WavUtils {

    /**
     * Parses WAV ByteArray to FloatArray samples.
     *
     * Assumes WAV format with 44-byte header and 16-bit signed PCM samples.
     * Converts to normalized float values in range [-1.0, 1.0].
     */
    fun parseWavToFloats(wavData: ByteArray): FloatArray {
        val headerSize = 44
        if (wavData.size <= headerSize) {
            throw IllegalArgumentException("WAV data too short: ${wavData.size} bytes")
        }

        val numSamples = (wavData.size - headerSize) / 2
        val samples = FloatArray(numSamples)

        val buffer = ByteBuffer.wrap(wavData, headerSize, wavData.size - headerSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val shortValue = buffer.short
            samples[i] = shortValue / 32768.0f
        }

        return samples
    }
}
