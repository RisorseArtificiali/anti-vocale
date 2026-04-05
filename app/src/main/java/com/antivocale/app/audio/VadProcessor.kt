package com.antivocale.app.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice Activity Detection processor using Silero VAD via sherpa-onnx.
 *
 * Strips silence from audio before transcription, reducing processing time
 * by 25-40% for typical voice messages.
 */
object VadProcessor {

    private const val TAG = "VadProcessor"
    private const val VAD_MODEL_PATH = "models/silero_vad.int8.onnx"
    private const val SAMPLE_RATE = 16000
    private const val WINDOW_SIZE = 512  // ~32ms at 16kHz
    private const val SPEECH_PAD_MS = 200  // minimal padding to avoid merging close segments
    private const val SPEECH_PAD_SAMPLES = SAMPLE_RATE * SPEECH_PAD_MS / 1000  // 3200 samples

    private class RawSegment(val start: Int, val end: Int)

data class VadResult(
        val speechSegments: List<FloatArray>,
        val totalSpeechDurationSeconds: Double,
        val segmentCount: Int,
        val originalDurationSeconds: Double
    )

    /**
     * Detects speech segments in the given PCM float samples.
     *
     * @param context Android context for loading the VAD model from assets
     * @param pcmSamples Float array of PCM samples normalized to [-1.0, 1.0]
     * @return VadResult containing speech segments and timing info
     */
    fun detectSpeech(context: Context, pcmSamples: FloatArray, threadCount: Int): VadResult {
        val startTime = System.currentTimeMillis()
        val originalDurationSeconds = pcmSamples.size.toDouble() / SAMPLE_RATE

        val sileroConfig = SileroVadModelConfig(
            model = VAD_MODEL_PATH,
            threshold = 0.5f,
            minSilenceDuration = 0.25f,   // 250ms silence to split
            minSpeechDuration = 0.25f,   // 250ms min speech (filter noise)
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = 20.0f    // well under Whisper's 30s limit
        )

        val vadConfig = VadModelConfig().apply {
            sileroVadModelConfig = sileroConfig
            tenVadModelConfig = TenVadModelConfig()  // empty, not used
            sampleRate = SAMPLE_RATE
            numThreads = threadCount
            provider = "cpu"
            debug = false
        }

        val vad = Vad(context.assets, vadConfig)

        try {
            // Feed samples in windows
            var offset = 0
            while (offset < pcmSamples.size) {
                val remaining = pcmSamples.size - offset
                val windowLen = minOf(WINDOW_SIZE, remaining)
                val window = pcmSamples.copyOfRange(offset, offset + windowLen)
                vad.acceptWaveform(window)
                offset += windowLen
            }

            // Drain segments emitted during acceptWaveform (BEFORE flush)
            val rawSegments = mutableListOf<RawSegment>()
            val totalSamples = pcmSamples.size

            while (!vad.empty()) {
                val segment: SpeechSegment = vad.front()
                val start = segment.start.coerceIn(0, totalSamples)
                val end = (segment.start + segment.samples.size).coerceIn(0, totalSamples)
                val sampleCount = end - start
                vad.pop()

                if (sampleCount >= SAMPLE_RATE / 4) {
                    rawSegments.add(RawSegment(start, end))
                }
            }

            // Flush to emit final pending segment
            vad.flush()

            while (!vad.empty()) {
                val segment: SpeechSegment = vad.front()
                val start = segment.start.coerceIn(0, totalSamples)
                val end = (segment.start + segment.samples.size).coerceIn(0, totalSamples)
                val sampleCount = end - start
                vad.pop()

                if (sampleCount >= SAMPLE_RATE / 4) {
                    rawSegments.add(RawSegment(start, end))
                }
            }

            // Apply minimal padding and merge overlapping segments (for progressive display)
            val finalSegments = if (rawSegments.isEmpty()) {
                Log.w(TAG, "VAD detected no speech, falling back to full audio")
                listOf(pcmSamples)
            } else {
                fun padStart(raw: RawSegment) = maxOf(0, raw.start - SPEECH_PAD_SAMPLES)
                fun padEnd(raw: RawSegment) = minOf(totalSamples, raw.end + SPEECH_PAD_SAMPLES)

                val merged = mutableListOf<Pair<Int, Int>>()
                var curStart = padStart(rawSegments[0])
                var curEnd = padEnd(rawSegments[0])

                for (i in 1 until rawSegments.size) {
                    val nextStart = padStart(rawSegments[i])
                    val nextEnd = padEnd(rawSegments[i])

                    if (nextStart <= curEnd) {
                        curEnd = maxOf(curEnd, nextEnd)
                    } else {
                        merged.add(curStart to curEnd)
                        curStart = nextStart
                        curEnd = nextEnd
                    }
                }
                merged.add(curStart to curEnd)

                merged.map { (start, end) -> pcmSamples.copyOfRange(start, end) }
            }

            val totalSpeechDuration = finalSegments.sumOf { it.size }.toDouble() / SAMPLE_RATE
            val vadDurationMs = System.currentTimeMillis() - startTime

            Log.i(TAG, "VAD completed in ${vadDurationMs}ms: ${finalSegments.size} segments, " +
                    "${"%.1f".format(totalSpeechDuration)}s speech from ${"%.1f".format(originalDurationSeconds)}s " +
                    "(+${SPEECH_PAD_MS}ms seg padding)")

            return VadResult(
                speechSegments = finalSegments,
                totalSpeechDurationSeconds = totalSpeechDuration,
                segmentCount = finalSegments.size,
                originalDurationSeconds = originalDurationSeconds
            )
        } finally {
            vad.release()
        }
    }

    /**
     * Converts raw 16-bit PCM bytes to float array normalized to [-1.0, 1.0].
     */
    fun pcmBytesToFloats(pcmData: ByteArray): FloatArray {
        val floatSamples = FloatArray(pcmData.size / 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (i in floatSamples.indices) {
            floatSamples[i] = buffer.short.toFloat() / Short.MAX_VALUE
        }

        return floatSamples
    }

    /**
     * Converts float array [-1.0, 1.0] back to 16-bit PCM bytes.
     */
    fun floatsToPcmBytes(samples: FloatArray): ByteArray {
        val pcmData = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
        }

        return pcmData
    }
}
