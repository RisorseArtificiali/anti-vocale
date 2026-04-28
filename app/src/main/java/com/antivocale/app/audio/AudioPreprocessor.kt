package com.antivocale.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles audio preprocessing for Gemma multimodal models (via LiteRT-LM).
 *
 * Converts audio files into 16kHz mono WAV ByteArrays using Android's
 * built-in MediaCodec and MediaExtractor APIs.
 *
 * Note: FFmpegKit was retired in January 2025. This implementation uses
 * native Android APIs which are more reliable and don't require external dependencies.
 */
@Singleton
class AudioPreprocessor @Inject constructor() {

    companion object {
        private const val TAG = "AudioPreprocessor"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
        private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB limit
        private const val MAX_DURATION_SECONDS = 600 // 10 minutes max
        private const val TIMEOUT_US = 10000L
    }

    /**
     * Result of audio preprocessing containing one or more audio chunks.
     */
    data class PreprocessingResult(
        val chunks: List<FloatArray>,
        val sampleRate: Int,
        val totalDurationSeconds: Double,
        val chunkCount: Int,
        val isVadSegmented: Boolean = false
    )

    /**
     * A single chunk emitted by the streaming preprocessor.
     */
    data class StreamChunk(
        val samples: FloatArray,
        val sampleRate: Int,
        val chunkIndex: Int,
        val isLast: Boolean
    )

    /**
     * Metadata emitted before the first stream chunk.
     */
    data class StreamHeader(
        val sampleRate: Int,
        val totalDurationSeconds: Double,
        val expectedChunkCount: Int
    )

    /**
     * Sealed output of the streaming preprocessor.
     */
    sealed class StreamEvent {
        data class Header(val header: StreamHeader) : StreamEvent()
        data class Chunk(val chunk: StreamChunk) : StreamEvent()
    }

    /**
     * Sealed class for preprocessing errors
     */
    sealed class PreprocessingError(message: String) : Exception(message) {
        data object FileNotFound : PreprocessingError("Audio file not found")
        data object FileTooLarge : PreprocessingError("Audio file exceeds 100MB limit")
        data object InvalidFormat : PreprocessingError("Unable to determine audio format")
        data object DurationTooLong : PreprocessingError("Audio exceeds 10 minute limit")
        data object DurationUnknown : PreprocessingError("Could not determine audio duration")
        data class ConversionFailed(val reason: String) : PreprocessingError("Conversion failed: $reason")
        data class ChunkFailed(val chunkIndex: Int, val reason: String) : PreprocessingError("Chunk $chunkIndex failed: $reason")
        data object NoAudioTrack : PreprocessingError("No audio track found in file")
    }

    /**
     * Prepares audio file for MediaPipe inference.
     *
     * @param inputPath Path to the source audio file
     * @param cacheDir Cache directory for intermediate files
     * @param maxChunkDurationSeconds Maximum chunk duration in seconds, *        null means no chunking (process entire audio as single chunk)
     * @return PreprocessingResult containing WAV chunks
     */
    fun prepareAudioForMediaPipe(
        inputPath: String,
        cacheDir: File,
        maxChunkDurationSeconds: Int? = 30,
        context: Context? = null,
        enableVad: Boolean = false,
        vadNumThreads: Int = 1,
        vadProvider: String = "cpu"
    ): PreprocessingResult {
        Log.d(TAG, "Preparing audio: $inputPath")
        validateInputFile(inputPath)

        // Extract and resample audio
        val audioData = extractToMonoFloat(inputPath)

        // Get duration
        val duration = audioData.samples.size.toDouble() / audioData.sampleRate

        if (duration > MAX_DURATION_SECONDS) {
            Log.e(TAG, "Audio too long: ${duration}s")
            throw PreprocessingError.DurationTooLong
        }

        Log.d(TAG, "Audio duration: ${duration}s")

        // Apply VAD silence stripping if enabled
        var samplesToProcess: FloatArray
        if (enableVad && context != null) {
            try {
                val floatSamples = audioData.samples
                val vadResult = VadProcessor.detectSpeech(context, floatSamples, vadNumThreads, vadProvider)
                val segments = vadResult.speechSegments

                // Multiple segments: merge adjacent ones up to 28s (WhisperX-style).
                // No overlap = no repetition. Boundaries are on VAD silence gaps.
                if (segments.size > 1) {
                    val maxMergeSamples = audioData.sampleRate * 28 // 28s, under Whisper's 30s limit

                    val mergedSegments = mutableListOf<FloatArray>()
                    var current = segments[0].clone()

                    for (i in 1 until segments.size) {
                        if (current.size + segments[i].size <= maxMergeSamples) {
                            // Merge: concatenate audio samples directly
                            val combined = FloatArray(current.size + segments[i].size)
                            System.arraycopy(current, 0, combined, 0, current.size)
                            System.arraycopy(segments[i], 0, combined, current.size, segments[i].size)
                            current = combined
                        } else {
                            mergedSegments.add(current)
                            current = segments[i].clone()
                        }
                    }
                    mergedSegments.add(current)

                    Log.i(TAG, "VAD progressive: ${segments.size} raw → ${mergedSegments.size} merged segments, " +
                            "${"%.1f".format(vadResult.originalDurationSeconds)}s → " +
                            "${"%.1f".format(vadResult.totalSpeechDurationSeconds)}s speech")
                    return PreprocessingResult(
                        chunks = mergedSegments,
                        sampleRate = audioData.sampleRate,
                        totalDurationSeconds = vadResult.totalSpeechDurationSeconds,
                        chunkCount = mergedSegments.size,
                        isVadSegmented = true
                    )
                }

                // Single segment: merge and use existing chunk logic
                val totalSize = segments.sumOf { it.size }
                val merged = FloatArray(totalSize)
                var offset = 0
                for (seg in segments) {
                    System.arraycopy(seg, 0, merged, offset, seg.size)
                    offset += seg.size
                }

                val strippedDuration = merged.size.toDouble() / audioData.sampleRate
                Log.i(TAG, "VAD stripped ${"%.1f".format(vadResult.originalDurationSeconds)}s → " +
                        "${"%.1f".format(strippedDuration)}s (${vadResult.segmentCount} segments)")

                samplesToProcess = merged
            } catch (e: Exception) {
                Log.e(TAG, "VAD processing failed, using full audio", e)
                samplesToProcess = audioData.samples
            }
        } else {
            samplesToProcess = audioData.samples
        }

        val processedDuration = samplesToProcess.size.toDouble() / audioData.sampleRate

        // Chunk if necessary
        if (maxChunkDurationSeconds == null || processedDuration <= maxChunkDurationSeconds) {
            return PreprocessingResult(
                chunks = listOf(samplesToProcess),
                sampleRate = audioData.sampleRate,
                totalDurationSeconds = processedDuration,
                chunkCount = 1
            )
        } else {
            return chunkFloatAudio(samplesToProcess, audioData.sampleRate, processedDuration, maxChunkDurationSeconds)
        }
    }

    /**
     * Streaming variant: produces audio chunks via Flow as MediaCodec decodes them.
     *
     * Emits StreamEvent.Header first (with total duration/chunk count), then
     * StreamEvent.Chunk for each transcription-sized chunk as it becomes available.
     * This allows the consumer to start transcribing chunk N while chunk N+1
     * is still being decoded.
     *
     * Falls back to collecting all chunks synchronously for VAD-enabled requests.
     */
    fun prepareAudioStream(
        inputPath: String,
        maxChunkDurationSeconds: Int? = 30,
        context: Context? = null,
        enableVad: Boolean = false,
        vadNumThreads: Int = 1,
        vadProvider: String = "cpu"
    ): Flow<StreamEvent> = flow {
        validateInputFile(inputPath)

        // VAD requires full audio — use synchronous path and emit results
        if (enableVad && context != null) {
            val result = prepareAudioForMediaPipe(
                inputPath = inputPath,
                cacheDir = File(File(inputPath).parent ?: "."),
                maxChunkDurationSeconds = maxChunkDurationSeconds,
                context = context,
                enableVad = true,
                vadNumThreads = vadNumThreads,
                vadProvider = vadProvider
            )
            emit(StreamEvent.Header(StreamHeader(
                sampleRate = result.sampleRate,
                totalDurationSeconds = result.totalDurationSeconds,
                expectedChunkCount = result.chunkCount
            )))
            result.chunks.forEachIndexed { i, chunk ->
                emit(StreamEvent.Chunk(StreamChunk(
                    samples = chunk,
                    sampleRate = result.sampleRate,
                    chunkIndex = i,
                    isLast = i == result.chunks.lastIndex
                )))
            }
            return@flow
        }

        // Streaming path: decode and emit chunks
        val channel = Channel<StreamEvent>(capacity = Channel.BUFFERED)
        val decoderThread = Thread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(inputPath)
                val audioTrackIndex = findAudioTrack(extractor)
                val inputFormat = extractor.getTrackFormat(audioTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

                val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
                val totalDurationSeconds = durationUs / 1_000_000.0
                if (totalDurationSeconds > MAX_DURATION_SECONDS) {
                    channel.close(PreprocessingError.DurationTooLong)
                    return@Thread
                }

                val chunkSamples = if (maxChunkDurationSeconds != null) inputSampleRate * maxChunkDurationSeconds else Int.MAX_VALUE
                val expectedChunks = if (maxChunkDurationSeconds != null) {
                    (totalDurationSeconds / maxChunkDurationSeconds).toInt().coerceAtLeast(1)
                } else 1

                val outputSampleRate = if (inputSampleRate != TARGET_SAMPLE_RATE) TARGET_SAMPLE_RATE else inputSampleRate

                channel.trySendBlocking(StreamEvent.Header(StreamHeader(
                    sampleRate = outputSampleRate,
                    totalDurationSeconds = totalDurationSeconds,
                    expectedChunkCount = expectedChunks
                )))

                val decoder = MediaCodec.createDecoderByType(mime)
                val accumulator = mutableListOf<FloatArray>()
                var accumulatedSamples = 0
                var chunkIndex = 0
                try {
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputEOS = false
                    var outputEOS = false

                    while (!outputEOS) {
                        if (!inputEOS) {
                            val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inputBufferIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEOS = true
                                } else {
                                    decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (outputBufferIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                var decoded = decodeChunkToMonoFloat(outputBuffer, bufferInfo, inputChannels)
                                if (inputSampleRate != TARGET_SAMPLE_RATE) {
                                    decoded = resampleFloat(decoded, inputSampleRate.toDouble() / TARGET_SAMPLE_RATE)
                                }
                                accumulator.add(decoded)
                                accumulatedSamples += decoded.size

                                val resampledChunkSamples = outputSampleRate * (maxChunkDurationSeconds ?: Int.MAX_VALUE / outputSampleRate)
                                while (accumulatedSamples >= resampledChunkSamples && maxChunkDurationSeconds != null) {
                                    val chunk = mergeAccumulated(accumulator, resampledChunkSamples)
                                    channel.trySendBlocking(StreamEvent.Chunk(StreamChunk(
                                        samples = chunk,
                                        sampleRate = outputSampleRate,
                                        chunkIndex = chunkIndex,
                                        isLast = false
                                    )))
                                    chunkIndex++
                                    accumulatedSamples = accumulator.sumOf { it.size }
                                }
                            }
                            decoder.releaseOutputBuffer(outputBufferIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEOS = true
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            Log.d(TAG, "Output format changed: ${decoder.outputFormat}")
                        }
                    }
                } finally {
                    try { decoder.stop() } catch (_: Exception) {}
                    decoder.release()
                }

                // Emit remaining samples as final chunk
                if (accumulator.isNotEmpty()) {
                    val remaining = mergeAccumulated(accumulator, Int.MAX_VALUE)
                    if (remaining.isNotEmpty()) {
                        channel.trySendBlocking(StreamEvent.Chunk(StreamChunk(
                            samples = remaining,
                            sampleRate = outputSampleRate,
                            chunkIndex = -1,
                            isLast = true
                        )))
                    }
                }
                channel.close()
            } catch (e: PreprocessingError) {
                channel.close(e)
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming audio", e)
                channel.close(PreprocessingError.ConversionFailed(e.message ?: "Unknown error"))
            } finally {
                extractor.release()
            }
        }
        decoderThread.start()

        try {
            for (event in channel) {
                emit(event)
            }
        } catch (e: PreprocessingError) {
            throw e
        } catch (e: Exception) {
            throw PreprocessingError.ConversionFailed(e.message ?: "Unknown error")
        } finally {
            decoderThread.interrupt()
        }
    }

    /**
     * Merges accumulated FloatArray chunks and extracts up to [maxSamples] samples.
     * Remaining samples are left in the accumulator.
     */
    private fun mergeAccumulated(accumulator: MutableList<FloatArray>, maxSamples: Int): FloatArray {
        val totalSize = accumulator.sumOf { it.size }
        if (totalSize == 0) return FloatArray(0)

        val extractSize = minOf(maxSamples, totalSize)
        val result = FloatArray(extractSize)
        var written = 0
        val iter = accumulator.iterator()

        while (iter.hasNext() && written < extractSize) {
            val chunk = iter.next()
            val toWrite = minOf(chunk.size, extractSize - written)
            System.arraycopy(chunk, 0, result, written, toWrite)
            written += toWrite

            if (toWrite < chunk.size) {
                // Partial consumption — replace with remaining portion
                iter.remove()
                accumulator.add(0, chunk.copyOfRange(toWrite, chunk.size))
                break
            } else {
                iter.remove()
            }
        }

        return result
    }

    private data class MonoAudioData(
        val samples: FloatArray,
        val sampleRate: Int
    )

    /**
     * Extracts audio from file and converts to mono FloatArray in a single pass.
     *
     * Reads directly from MediaCodec's output ByteBuffer, performs stereo→mono
     * averaging and normalizes to float [-1.0, 1.0] without intermediate ByteArrays.
     * Resamples to 16kHz when input differs — avoids forcing backends to
     * process ratio× more data than necessary.
     */
    private fun extractToMonoFloat(inputPath: String): MonoAudioData {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(inputPath)

            val audioTrackIndex = findAudioTrack(extractor)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            extractor.selectTrack(audioTrackIndex)

            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            Log.d(TAG, "Input: $mime, ${inputSampleRate}Hz, $inputChannels channels")

            val decoder = MediaCodec.createDecoderByType(mime)
            val sampleChunks = mutableListOf<FloatArray>()
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputEOS = false
                var outputEOS = false

                while (!outputEOS) {
                    if (!inputEOS) {
                        val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEOS = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)

                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = decodeChunkToMonoFloat(outputBuffer, bufferInfo, inputChannels)
                            sampleChunks.add(chunk)
                        }

                        decoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEOS = true
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed: ${decoder.outputFormat}")
                    }
                }
            } finally {
                try { decoder.stop() } catch (_: Exception) {}
                decoder.release()
            }

            // Merge chunks into single FloatArray
            val totalSamples = sampleChunks.sumOf { it.size }
            val merged = FloatArray(totalSamples)
            var offset = 0
            for (chunk in sampleChunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.size)
                offset += chunk.size
            }

            val (finalSamples, finalRate) = if (inputSampleRate != TARGET_SAMPLE_RATE) {
                val resampled = resampleFloat(merged, inputSampleRate.toDouble() / TARGET_SAMPLE_RATE)
                Log.d(TAG, "Resampled ${merged.size} samples ${inputSampleRate}Hz → ${resampled.size} samples ${TARGET_SAMPLE_RATE}Hz")
                Pair(resampled, TARGET_SAMPLE_RATE)
            } else {
                Pair(merged, inputSampleRate)
            }

            Log.d(TAG, "Extracted ${finalSamples.size} mono float samples at ${finalRate}Hz")
            return MonoAudioData(samples = finalSamples, sampleRate = finalRate)

        } catch (e: PreprocessingError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio", e)
            throw PreprocessingError.ConversionFailed(e.message ?: "Unknown error")
        } finally {
            extractor.release()
        }
    }

    private fun validateInputFile(inputPath: String) {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) throw PreprocessingError.FileNotFound
        val fileSize = inputFile.length()
        if (fileSize > MAX_FILE_SIZE_BYTES) throw PreprocessingError.FileTooLarge
        if (fileSize == 0L) throw PreprocessingError.InvalidFormat
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        throw PreprocessingError.NoAudioTrack
    }

    /**
     * Converts a MediaCodec output chunk directly to mono FloatArray.
     *
     * Reads 16-bit PCM from the ByteBuffer, performs stereo→mono averaging,
     * and normalizes to [-1.0, 1.0] in a single pass — no intermediate ByteArrays.
     */
    private fun decodeChunkToMonoFloat(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int
    ): FloatArray {
        val bytesPerFrame = channels * 2 // 16-bit = 2 bytes per channel
        val numFrames = info.size / bytesPerFrame
        val samples = FloatArray(numFrames)

        buffer.position(info.offset)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numFrames) {
            samples[i] = if (channels == 2) {
                val left = buffer.short
                val right = buffer.short
                ((left.toInt() + right.toInt()) / 2) / 32768.0f
            } else {
                buffer.short / 32768.0f
            }
        }

        return samples
    }

    /**
     * Resamples a FloatArray from a higher sample rate to 16kHz using linear interpolation.
     * [ratio] = inputRate / targetRate (e.g. 48000/16000 = 3.0).
     *
     * Performance-critical: without this, high-rate input (e.g. 48kHz OGG) passes ratio×
     * more samples to sherpa-onnx's internal resampler. Pre-resampling here cuts the data
     * to 1/ratio of the original — on-device benchmarking showed ~2x Parakeet slowdown
     * when this was accidentally removed.
     */
    private fun resampleFloat(input: FloatArray, ratio: Double): FloatArray {
        val outputSize = (input.size / ratio).toInt()
        val output = FloatArray(outputSize)

        for (i in 0 until outputSize) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()

            if (srcIndex < input.size - 1) {
                val frac = (srcPos - srcIndex).toFloat()
                output[i] = input[srcIndex] * (1.0f - frac) + input[srcIndex + 1] * frac
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }

        return output
    }

    /**
     * Chunks float audio data into segments of specified duration.
     */
    private fun chunkFloatAudio(
        samples: FloatArray,
        sampleRate: Int,
        duration: Double,
        maxChunkDurationSeconds: Int
    ): PreprocessingResult {
        val samplesPerChunk = sampleRate * maxChunkDurationSeconds
        val chunks = mutableListOf<FloatArray>()
        var offset = 0
        var chunkIndex = 0

        while (offset < samples.size) {
            val chunkSize = minOf(samplesPerChunk, samples.size - offset)
            chunks.add(samples.copyOfRange(offset, offset + chunkSize))

            Log.d(TAG, "Created chunk $chunkIndex: $chunkSize samples")

            offset += chunkSize
            chunkIndex++
        }

        return PreprocessingResult(
            chunks = chunks,
            sampleRate = sampleRate,
            totalDurationSeconds = duration,
            chunkCount = chunks.size
        )
    }

    /**
     * Gets audio duration using MediaExtractor.
     */
    fun getAudioDuration(inputPath: String): Double {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    extractor.release()
                    return durationUs / 1_000_000.0
                }
            }

            extractor.release()
            return 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio duration", e)
            return 0.0
        }
    }

    /**
     * Cancels any ongoing operations (no-op for MediaCodec implementation).
     */
    fun cancelAll() {
        // No-op - MediaCodec operations are synchronous
    }

    /**
     * Gets audio info for debugging.
     */
    fun getAudioInfo(inputPath: String): String {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val info = StringBuilder()
            info.append("Tracks: ${extractor.trackCount}\n")

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                info.append("Track $i: ${format.getString(MediaFormat.KEY_MIME)}\n")
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    info.append("  Sample rate: ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)}\n")
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    info.append("  Channels: ${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}\n")
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    info.append("  Duration: ${durationUs / 1_000_000.0}s\n")
                }
            }

            extractor.release()
            return info.toString()
        } catch (e: Exception) {
            return "Error getting audio info: ${e.message}"
        }
    }
}
