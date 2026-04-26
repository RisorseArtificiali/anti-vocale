package com.antivocale.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility functions for WAV audio data processing.
 */
object WavUtils {

    private const val WAV_HEADER_SIZE = 44

    /**
     * Generates a silent WAV PCM byte array with the given parameters.
     * Audio data is all zeros (silence).
     */
    fun generateSilence(
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        durationSeconds: Float = 10f
    ): ByteArray {
        val numSamples = (sampleRate * durationSeconds).toInt()
        val dataSize = numSamples * channels * (bitsPerSample / 8)
        val buffer = ByteArray(WAV_HEADER_SIZE + dataSize)

        buffer[0] = 'R'.code.toByte(); buffer[1] = 'I'.code.toByte()
        buffer[2] = 'F'.code.toByte(); buffer[3] = 'F'.code.toByte()
        writeIntLE(buffer, 4, 36 + dataSize)
        buffer[8] = 'W'.code.toByte(); buffer[9] = 'A'.code.toByte()
        buffer[10] = 'V'.code.toByte(); buffer[11] = 'E'.code.toByte()
        buffer[12] = 'f'.code.toByte(); buffer[13] = 'm'.code.toByte()
        buffer[14] = 't'.code.toByte(); buffer[15] = ' '.code.toByte()
        writeIntLE(buffer, 16, 16)
        writeShortLE(buffer, 20, 1)
        writeShortLE(buffer, 22, channels.toShort())
        writeIntLE(buffer, 24, sampleRate)
        writeIntLE(buffer, 28, sampleRate * channels * (bitsPerSample / 8))
        writeShortLE(buffer, 32, (channels * (bitsPerSample / 8)).toShort())
        writeShortLE(buffer, 34, bitsPerSample.toShort())
        buffer[36] = 'd'.code.toByte(); buffer[37] = 'a'.code.toByte()
        buffer[38] = 't'.code.toByte(); buffer[39] = 'a'.code.toByte()
        writeIntLE(buffer, 40, dataSize)
        return buffer
    }

    fun readLittleEndianInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = (value shr 8 and 0xFF).toByte()
        buffer[offset + 2] = (value shr 16 and 0xFF).toByte()
        buffer[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xFF).toByte()
        buffer[offset + 1] = (value.toInt() shr 8 and 0xFF).toByte()
    }

    /**
     * Parses WAV ByteArray to FloatArray samples.
     *
     * Assumes WAV format with 44-byte header and 16-bit signed PCM samples.
     * Converts to normalized float values in range [-1.0, 1.0].
     */
    fun parseWavToFloats(wavData: ByteArray): FloatArray {
        if (wavData.size <= WAV_HEADER_SIZE) {
            throw IllegalArgumentException("WAV data too short: ${wavData.size} bytes")
        }

        val numSamples = (wavData.size - WAV_HEADER_SIZE) / 2
        val samples = FloatArray(numSamples)

        val buffer = ByteBuffer.wrap(wavData, WAV_HEADER_SIZE, wavData.size - WAV_HEADER_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val shortValue = buffer.short
            samples[i] = shortValue / 32768.0f
        }

        return samples
    }
}
