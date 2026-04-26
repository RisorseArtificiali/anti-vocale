package com.antivocale.app.benchmark

import com.antivocale.app.util.WavUtils
import org.junit.Assert.*
import org.junit.Test

class BenchmarkResultTest {

    // ==================== secondsPerMinute ====================

    @Test
    fun `secondsPerMinute extrapolates from 10-second sample`() {
        val result = BenchmarkResult("test", 5000, 10f, 100f)
        assertEquals(30f, result.secondsPerMinute, 0.01f)
    }

    @Test
    fun `secondsPerMinute with 30-second sample`() {
        val result = BenchmarkResult("test", 15000, 30f, 100f)
        assertEquals(30f, result.secondsPerMinute, 0.01f)
    }

    @Test
    fun `secondsPerMinute returns zero for zero duration`() {
        val result = BenchmarkResult("test", 5000, 0f, 100f)
        assertEquals(0f, result.secondsPerMinute, 0.01f)
    }

    @Test
    fun `secondsPerMinute for very fast model`() {
        val result = BenchmarkResult("test", 1000, 10f, 100f)
        // 1s for 10s → 6s/min
        assertEquals(6f, result.secondsPerMinute, 0.01f)
    }

    // ==================== rating ====================

    @Test
    fun `rating FAST for under 15 seconds per minute`() {
        val result = BenchmarkResult("test", 2000, 10f, 100f)
        assertEquals(SpeedRating.FAST, result.rating)
    }

    @Test
    fun `rating GOOD for 15-30 seconds per minute`() {
        val result = BenchmarkResult("test", 4000, 10f, 100f)
        assertEquals(SpeedRating.GOOD, result.rating)
    }

    @Test
    fun `rating MODERATE for 30-60 seconds per minute`() {
        val result = BenchmarkResult("test", 7500, 10f, 100f)
        assertEquals(SpeedRating.MODERATE, result.rating)
    }

    @Test
    fun `rating SLOW for over 60 seconds per minute`() {
        val result = BenchmarkResult("test", 12000, 10f, 100f)
        assertEquals(SpeedRating.SLOW, result.rating)
    }

    @Test
    fun `rating boundary FAST at exactly 15`() {
        val result = BenchmarkResult("test", 2500, 10f, 100f)
        assertEquals(SpeedRating.FAST, result.rating)
    }

    @Test
    fun `rating boundary GOOD at exactly 30`() {
        val result = BenchmarkResult("test", 5000, 10f, 100f)
        assertEquals(SpeedRating.GOOD, result.rating)
    }

    @Test
    fun `rating boundary MODERATE at exactly 60`() {
        val result = BenchmarkResult("test", 10000, 10f, 100f)
        assertEquals(SpeedRating.MODERATE, result.rating)
    }

    // ==================== JSON serialization ====================

    @Test
    fun `toJson and fromJson roundtrip`() {
        val original = BenchmarkResult("whisper", 4500, 10f, 256.5f, 1700000000000L)
        val json = original.toJson()
        val restored = BenchmarkResult.fromJson(json)

        assertNotNull(restored)
        assertEquals(original.modelId, restored!!.modelId)
        assertEquals(original.inferenceTimeMs, restored.inferenceTimeMs)
        assertEquals(original.audioDurationSeconds, restored.audioDurationSeconds, 0.01f)
        assertEquals(original.peakMemoryMb, restored.peakMemoryMb, 0.01f)
        assertEquals(original.timestamp, restored.timestamp)
    }

    @Test
    fun `fromJson preserves computed properties`() {
        val original = BenchmarkResult("test", 4000, 10f, 100f)
        val restored = BenchmarkResult.fromJson(original.toJson())!!

        assertEquals(original.secondsPerMinute, restored.secondsPerMinute, 0.01f)
        assertEquals(original.rating, restored.rating)
    }

    @Test
    fun `fromJson returns null for invalid JSON`() {
        assertNull(BenchmarkResult.fromJson("not json"))
    }

    @Test
    fun `fromJson returns null for empty string`() {
        assertNull(BenchmarkResult.fromJson(""))
    }

    @Test
    fun `fromJson handles missing optional timestamp`() {
        val json = """{"modelId":"test","inferenceTimeMs":5000,"audioDurationSeconds":10.0,"peakMemoryMb":100.0}"""
        val result = BenchmarkResult.fromJson(json)
        assertNotNull(result)
        assertEquals("test", result!!.modelId)
    }

    // ==================== WAV generation ====================

    @Test
    fun `generateSilence produces valid RIFF WAVE header`() {
        val audio = WavUtils.generateSilence()

        assertEquals('R'.code.toByte(), audio[0])
        assertEquals('I'.code.toByte(), audio[1])
        assertEquals('F'.code.toByte(), audio[2])
        assertEquals('F'.code.toByte(), audio[3])
        assertEquals('W'.code.toByte(), audio[8])
        assertEquals('A'.code.toByte(), audio[9])
        assertEquals('V'.code.toByte(), audio[10])
        assertEquals('E'.code.toByte(), audio[11])
    }

    @Test
    fun `generateSilence has correct size for 10s 16kHz mono 16-bit`() {
        val audio = WavUtils.generateSilence()
        val expectedSize = 44 + (16000 * 10 * 1 * 2)
        assertEquals(expectedSize, audio.size)
    }

    @Test
    fun `generateSilence data chunk header is correct`() {
        val audio = WavUtils.generateSilence()
        assertEquals('d'.code.toByte(), audio[36])
        assertEquals('a'.code.toByte(), audio[37])
        assertEquals('t'.code.toByte(), audio[38])
        assertEquals('a'.code.toByte(), audio[39])
    }

    @Test
    fun `generateSilence audio data is silence`() {
        val audio = WavUtils.generateSilence()
        for (i in 44 until audio.size) {
            assertEquals("Sample at index $i should be zero (silence)", 0, audio[i].toInt())
        }
    }

    @Test
    fun `generateSilence fmt chunk specifies PCM mono 16kHz 16-bit`() {
        val audio = WavUtils.generateSilence()

        // AudioFormat = 1 (PCM) at offset 20
        val audioFormat = (audio[20].toInt() and 0xFF) or ((audio[21].toInt() and 0xFF) shl 8)
        assertEquals(1, audioFormat)

        // NumChannels = 1 at offset 22
        val channels = (audio[22].toInt() and 0xFF) or ((audio[23].toInt() and 0xFF) shl 8)
        assertEquals(1, channels)

        // SampleRate = 16000 at offset 24
        val sampleRate = WavUtils.readLittleEndianInt(audio, 24)
        assertEquals(16000, sampleRate)

        // BitsPerSample = 16 at offset 34
        val bitsPerSample = (audio[34].toInt() and 0xFF) or ((audio[35].toInt() and 0xFF) shl 8)
        assertEquals(16, bitsPerSample)
    }
}
