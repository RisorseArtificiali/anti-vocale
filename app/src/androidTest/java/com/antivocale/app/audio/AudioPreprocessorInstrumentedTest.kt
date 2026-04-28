package com.antivocale.app.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumented tests for AudioPreprocessor.
 *
 * These tests run on an Android device/emulator and test the actual
 * MediaCodec/MediaExtractor integration.
 *
 * Prerequisites:
 * - Device/emulator must have storage permissions granted
 * - Test audio files should be placed in assets or generated programmatically
 */
@RunWith(AndroidJUnit4::class)
class AudioPreprocessorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var preprocessor: AudioPreprocessor

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = context.cacheDir
        preprocessor = AudioPreprocessor()
    }

    @Test
    fun getAudioDuration_withNonExistentFile_returnsZero() {
        val nonExistentPath = "/non/existent/file.m4a"
        val duration = preprocessor.getAudioDuration(nonExistentPath)

        assertEquals("Non-existent file should return 0 duration", 0.0, duration, 0.001)
    }

    @Test
    fun prepareAudioForMediaPipe_withNonExistentFile_throwsFileNotFound() {
        val nonExistentPath = "/non/existent/file.m4a"

        val exception = assertThrows(AudioPreprocessor.PreprocessingError.FileNotFound::class.java) {
            preprocessor.prepareAudioForMediaPipe(nonExistentPath, cacheDir)
        }

        assertEquals("Audio file not found", exception.message)
    }

    @Test
    fun prepareAudioForMediaPipe_withEmptyFile_throwsInvalidFormat() {
        val emptyFile = File(cacheDir, "empty_audio.m4a")
        emptyFile.createNewFile()

        try {
            val exception = assertThrows(AudioPreprocessor.PreprocessingError::class.java) {
                preprocessor.prepareAudioForMediaPipe(emptyFile.absolutePath, cacheDir)
            }

            assertTrue(
                "Should throw InvalidFormat for empty file",
                exception is AudioPreprocessor.PreprocessingError.InvalidFormat
            )
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun cancelAll_doesNotThrow() {
        preprocessor.cancelAll()
    }

    @Test
    fun getAudioInfo_withNonExistentFile_returnsErrorMessage() {
        val nonExistentPath = "/non/existent/file.m4a"
        val info = preprocessor.getAudioInfo(nonExistentPath)

        assertEquals("Unable to get audio info", info)
    }

    @Test
    fun preprocessingResult_dataClass_worksCorrectly() {
        val chunks = listOf(
            byteArrayOf(0, 1, 2, 3, 4),
            byteArrayOf(5, 6, 7, 8, 9)
        )

        val result = AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            totalDurationSeconds = 45.5,
            chunkCount = 2
        )

        assertEquals(2, result.chunks.size)
        assertEquals(45.5, result.totalDurationSeconds, 0.001)
        assertEquals(2, result.chunkCount)
    }

    // ========== Helper to create test audio file ==========

    private fun createTestWavFile(): File {
        val wavFile = File(cacheDir, "test_audio.wav")

        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val durationSeconds = 1
        val numSamples = sampleRate * durationSeconds
        val dataSize = numSamples * channels * bitsPerSample / 8

        FileOutputStream(wavFile).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(36 + dataSize))
            fos.write("WAVE".toByteArray())

            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16))
            fos.write(shortToLittleEndian(1))
            fos.write(shortToLittleEndian(channels))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(sampleRate * channels * bitsPerSample / 8))
            fos.write(shortToLittleEndian(channels * bitsPerSample / 8))
            fos.write(shortToLittleEndian(bitsPerSample))

            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(dataSize))

            val silence = ByteArray(dataSize)
            fos.write(silence)
        }

        return wavFile
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but no exception was thrown")
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
    }
}
