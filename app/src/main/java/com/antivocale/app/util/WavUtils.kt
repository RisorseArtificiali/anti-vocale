package com.antivocale.app.util

import java.io.ByteArrayOutputStream
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

    /**
     * Creates a WAV byte array from mono FloatArray samples.
     * Used by LLM backends that require WAV format (LiteRT-LM Content.AudioBytes).
     */
    fun floatSamplesToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmData = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in samples.indices) {
            buf.put(i, (samples[i] * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }

        val channels = 1
        val bitsPerSample = 16
        val wavData = ByteArrayOutputStream()
        wavData.write("RIFF".toByteArray())
        writeIntLE(wavData, 36 + pcmData.size)
        wavData.write("WAVE".toByteArray())
        wavData.write("fmt ".toByteArray())
        writeIntLE(wavData, 16)
        writeShortLE(wavData, 1)
        writeShortLE(wavData, channels)
        writeIntLE(wavData, sampleRate)
        writeIntLE(wavData, sampleRate * channels * bitsPerSample / 8)
        writeShortLE(wavData, channels * bitsPerSample / 8)
        writeShortLE(wavData, bitsPerSample)
        wavData.write("data".toByteArray())
        writeIntLE(wavData, pcmData.size)
        wavData.write(pcmData)
        return wavData.toByteArray()
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

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        ))
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xFF).toByte()
        buffer[offset + 1] = (value.toInt() shr 8 and 0xFF).toByte()
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        ))
    }
}
