package com.antivocale.app.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.toList
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

    // ========== TASK-432: metadata pre-read duration enforcement ==========
    // MediaExtractor is real here (JVM harness stubs it), so the behavioral
    // duration tests live in this file. WAV fixture: 8kHz mono s16 (16KB/s) so
    // durations stay small on disk; duration comes from the data-chunk size.

    @Test
    fun prepareAudioForMediaPipe_11minFile_passesWithPermissiveReadings() {
        val wav = createWavWithDurationSeconds(660.0)
        try {
            // Old flat cap refused at 600s; permissive readings give a 1365s ceiling
            val result = preprocessor.prepareAudioForMediaPipe(
                inputPath = wav.absolutePath,
                cacheDir = cacheDir,
                maxChunkDurationSeconds = null,
                availableRamBytes = 32L shl 30,
                maxHeapBytes = 512L shl 20
            )
            assertEquals(1, result.chunkCount)
            assertEquals(660.0, result.totalDurationSeconds, 2.0)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun prepareAudioStream_above2hFile_throwsDurationTooLongWithStreamingCeiling() {
        val wav = createWavWithDurationSeconds(7210.0)
        try {
            val exception = assertThrows(AudioPreprocessor.PreprocessingError.DurationTooLong::class.java) {
                // The flow body (validateDuration included) runs at collection
                preprocessor.prepareAudioStream(
                    inputPath = wav.absolutePath,
                    maxChunkDurationSeconds = 30,
                    availableRamBytes = 8L shl 30,
                    maxHeapBytes = 512L shl 20
                ).let { flow -> kotlinx.coroutines.runBlocking { flow.toList() } }
            }
            assertEquals(7200L, exception.ceilingSeconds)
            assertEquals(AudioDurationPolicy.DecodePath.STREAMING, exception.path)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun prepareAudioForMediaPipe_headerDeclaresLongDuration_throwsBeforeDecoding() {
        // Container metadata declares 7210s; the actual data payload is truncated.
        // The pre-read must refuse from metadata alone: a decode attempt on this
        // file would read a fraction of the declared data, so reaching the decoder
        // with a full-length transcript would be the bug this test guards against.
        val wav = createWavWithDurationSeconds(7210.0, actualDataFraction = 0.001f)
        try {
            val exception = assertThrows(AudioPreprocessor.PreprocessingError.DurationTooLong::class.java) {
                preprocessor.prepareAudioForMediaPipe(
                    inputPath = wav.absolutePath,
                    cacheDir = cacheDir,
                    maxChunkDurationSeconds = null,
                    availableRamBytes = 32L shl 30,
                    maxHeapBytes = 512L shl 20
                )
            }
            assertEquals(7200L, exception.ceilingSeconds)
            assertEquals(AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, exception.path)
        } finally {
            wav.delete()
        }
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
            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f),
            floatArrayOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        )

        val result = AudioPreprocessor.PreprocessingResult(
            chunks = chunks,
            sampleRate = 16000,
            totalDurationSeconds = 45.5,
            chunkCount = 2
        )

        assertEquals(2, result.chunks.size)
        assertEquals(45.5, result.totalDurationSeconds, 0.001)
        assertEquals(2, result.chunkCount)
    }

    // ========== Helper to create test audio file ==========

    /**
     * TASK-432 duration fixture: a valid WAV header declaring [durationSeconds]
     * of 8kHz mono s16 PCM. [actualDataFraction] < 1 writes only part of the
     * declared payload (truncated file, header keeps promising more).
     */
    private fun createWavWithDurationSeconds(
        durationSeconds: Double,
        actualDataFraction: Float = 1.0f,
    ): File {
        val sampleRate = 8000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val declaredDataSize = (durationSeconds * byteRate).toInt()
        val actualDataSize = (declaredDataSize * actualDataFraction).toInt().coerceAtLeast(44)

        val wavFile = File(cacheDir, "duration_${durationSeconds.toInt()}s_${System.nanoTime()}.wav")
        java.io.BufferedOutputStream(FileOutputStream(wavFile)).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(36 + declaredDataSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16))
            fos.write(shortToLittleEndian(1))
            fos.write(shortToLittleEndian(channels))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(channels * bitsPerSample / 8))
            fos.write(shortToLittleEndian(bitsPerSample))
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(declaredDataSize))
            val zeros = ByteArray(64 * 1024)
            var remaining = actualDataSize
            while (remaining > 0) {
                val n = minOf(remaining, zeros.size)
                fos.write(zeros, 0, n)
                remaining -= n
            }
        }
        return wavFile
    }

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
