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

        /**
         * VAD segments are merged up to the model's per-segment limit minus a 2s
         * margin (GH #50): 30s-limit models keep the historical 28s window, a
         * 60s-limit model (Parakeet since TASK-406) merges speech into large
         * segments instead of many Whisper-sized ones.
         */
        internal fun vadMergeLimitSeconds(maxChunkDurationSeconds: Int?): Int =
            ((maxChunkDurationSeconds ?: 30) - 2).coerceAtLeast(1)
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
        private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB sanity bound
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
        data object FileTooLarge : PreprocessingError("Audio file exceeds 2GB limit")
        data object InvalidFormat : PreprocessingError("Unable to determine audio format")
        data class DurationTooLong(val ceilingSeconds: Long, val path: AudioDurationPolicy.DecodePath) :
            PreprocessingError("Audio exceeds ${ceilingSeconds / 60} minute limit on this path")
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
        vadProvider: String = "cpu",
        availableRamBytes: Long? = null,
        maxHeapBytes: Long? = null
    ): PreprocessingResult {
        Log.d(TAG, "Preparing audio: $inputPath")
        validateInputFile(inputPath)
        val ceiling = durationCeiling(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, availableRamBytes, maxHeapBytes)
        validateDuration(inputPath, AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ceiling)

        // Extract and resample audio
        val audioData = extractToMonoFloat(inputPath)

        // Get duration
        val duration = audioData.samples.size.toDouble() / audioData.sampleRate

        // Post-decode backstop for metadata-less containers: the pre-read fails
        // open when KEY_DURATION is absent, so for those files this decoded
        // length is the only ceiling evidence (the PCM is already resident here,
        // but that matches the pre-1.12 behavior instead of capping nothing).
        if (duration > ceiling) {
            Log.e(TAG, "Audio too long (post-decode): ${duration}s > ${ceiling}s ceiling")
            throw PreprocessingError.DurationTooLong(ceiling, AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM)
        }

        Log.d(TAG, "Audio duration: ${duration}s")

        // Apply VAD silence stripping if enabled
        var samplesToProcess: FloatArray
        if (enableVad && context != null) {
            try {
                val floatSamples = audioData.samples
                val vadResult = VadProcessor.detectSpeech(context, floatSamples, vadNumThreads, vadProvider)
                // Local reference-copy so the raw VAD output can be dropped (cleared)
                // as soon as the merged output exists, freeing it while the caller
                // proceeds (TASK-340 Fix 3).
                val segments = vadResult.speechSegments.toMutableList()

                // Multiple segments: merge adjacent ones up to the model's per-segment
                // limit minus a small margin (WhisperX-style; historically 28s for
                // Whisper's 30s window). No overlap = no repetition. Boundaries are on
                // VAD silence gaps.
                if (segments.size > 1) {
                    val maxMergeSamples = audioData.sampleRate * vadMergeLimitSeconds(maxChunkDurationSeconds)

                    val mergedSegments = mergeVadSegments(segments, maxMergeSamples)
                    segments.clear()

                    Log.i(TAG, "VAD progressive: ${vadResult.speechSegments.size} raw → ${mergedSegments.size} merged segments, " +
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
                segments.clear()

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
        vadProvider: String = "cpu",
        availableRamBytes: Long? = null,
        maxHeapBytes: Long? = null
    ): Flow<StreamEvent> = flow {
        validateInputFile(inputPath)

        // The whole-file delegation below validates with its own (tighter)
        // ceiling: pre-reading STREAMING here would duplicate the metadata
        // open and refuse with the wrong path label for a request that
        // actually decodes whole-file.
        val decodeWholeFile = enableVad && context != null
        if (!decodeWholeFile) {
            validateDuration(
                inputPath,
                AudioDurationPolicy.DecodePath.STREAMING,
                durationCeiling(AudioDurationPolicy.DecodePath.STREAMING, availableRamBytes, maxHeapBytes))
        }

        // VAD requires full audio — use synchronous path and emit results
        if (decodeWholeFile) {
            val result = prepareAudioForMediaPipe(
                inputPath = inputPath,
                cacheDir = File(File(inputPath).parent ?: "."),
                maxChunkDurationSeconds = maxChunkDurationSeconds,
                context = context,
                enableVad = true,
                vadNumThreads = vadNumThreads,
                vadProvider = vadProvider,
                availableRamBytes = availableRamBytes,
                maxHeapBytes = maxHeapBytes
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
            // TASK-416: resample per decode chunk through the streaming resampler so
            // the input-rate signal is never held whole (the old collect-then-merge
            // held chunks + merged copy, ~230MB for a 10-minute 48kHz file: the
            // Crashlytics Java-heap OOM class). sampleChunks collect 16kHz output.
            val streamResampler =
                if (inputSampleRate != TARGET_SAMPLE_RATE)
                    SincStreamResampler(inputSampleRate.toDouble() / TARGET_SAMPLE_RATE) else null
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
                            val targetRateChunk = streamResampler?.process(chunk) ?: chunk
                            if (targetRateChunk.isNotEmpty()) sampleChunks.add(targetRateChunk)
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

            // Flush the resampler tail (outputs whose window crossed EOS), then merge.
            val tailChunk = streamResampler?.flush()
            if (tailChunk != null && tailChunk.isNotEmpty()) sampleChunks.add(tailChunk)
            // Chunks arrive at TARGET rate (streaming resample above): the merge is
            // a plain concatenation (mergeAndResample's target-rate branch); its
            // peak is 2x the FINAL size, not 2x the input-rate signal (TASK-416;
            // the collect-then-merge double-buffer of TASK-340 Fix 1a is superseded).
            val (finalSamples, finalRate) = mergeAndResample(sampleChunks, TARGET_SAMPLE_RATE)
            sampleChunks.clear()

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

    /**
     * Merges the decoded input-rate chunks and resamples to 16kHz. Local references to
     * the chunk list and the merged input-rate buffer are dropped (clear + reassign to an
     * empty array) before returning, so they are collectable while the caller proceeds
     * to chunk processing (TASK-340 Fix 1a).
     *
     * Since TASK-416 the production caller feeds already-resampled 16kHz chunks, so the
     * resampling branch below is exercised only by its pinning tests.
     */
    internal fun mergeAndResample(chunks: MutableList<FloatArray>, inputSampleRate: Int): Pair<FloatArray, Int> {
        var list = chunks
        val totalSamples = list.sumOf { it.size }
        var merged = FloatArray(totalSamples)
        var offset = 0
        for (chunk in list) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        list.clear()
        list = mutableListOf()

        return if (inputSampleRate != TARGET_SAMPLE_RATE) {
            val resampled = resampleFloat(merged, inputSampleRate.toDouble() / TARGET_SAMPLE_RATE)
            Log.d(TAG, "Resampled ${merged.size} samples ${inputSampleRate}Hz → ${resampled.size} samples ${TARGET_SAMPLE_RATE}Hz")
            merged = FloatArray(0)
            Pair(resampled, TARGET_SAMPLE_RATE)
        } else {
            Pair(merged, inputSampleRate)
        }
    }

    /**
     * Merges adjacent VAD segments up to [maxMergeSamples] per output group
     * (WhisperX-style: no overlap, boundaries on VAD silence gaps).
     *
     * Two-pass per group (sizes, then copy) so each output is a single
     * allocation instead of an O(N²) chain of intermediate arrays
     * (TASK-340 Fix 3).
     */
    internal fun mergeVadSegments(segments: List<FloatArray>, maxMergeSamples: Int): List<FloatArray> {
        val merged = mutableListOf<FloatArray>()
        var start = 0
        while (start < segments.size) {
            // Pass 1: find the extent of this group and its total size.
            var groupSize = segments[start].size
            var end = start
            while (end + 1 < segments.size && groupSize + segments[end + 1].size <= maxMergeSamples) {
                end++
                groupSize += segments[end].size
            }

            // Pass 2: one allocation, copy each segment in.
            if (end == start && segments[start].size > maxMergeSamples) {
                // A single raw VAD segment longer than the merge limit (unbroken
                // speech): split it at the limit so it cannot bypass the model's
                // per-segment cap (GH #50 review finding).
                val seg = segments[start]
                var offset = 0
                while (offset < seg.size) {
                    val len = minOf(maxMergeSamples, seg.size - offset)
                    merged.add(seg.copyOfRange(offset, offset + len))
                    offset += len
                }
            } else if (end == start) {
                merged.add(segments[start])
            } else {
                val combined = FloatArray(groupSize)
                var offset = 0
                for (i in start..end) {
                    System.arraycopy(segments[i], 0, combined, offset, segments[i].size)
                    offset += segments[i].size
                }
                merged.add(combined)
            }
            start = end + 1
        }
        return merged
    }

    private fun validateInputFile(inputPath: String) {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) throw PreprocessingError.FileNotFound
        val fileSize = inputFile.length()
        if (fileSize > MAX_FILE_SIZE_BYTES) throw PreprocessingError.FileTooLarge
        if (fileSize == 0L) throw PreprocessingError.InvalidFormat
    }

    private fun durationCeiling(
        path: AudioDurationPolicy.DecodePath,
        availableRamBytes: Long?,
        maxHeapBytes: Long?,
    ): Long {
        if (availableRamBytes == null || maxHeapBytes == null) {
            // Visible so a future caller omitting the readings cannot silently
            // reintroduce a flat 600s cap looking like a low-memory device.
            Log.w(TAG, "Duration ceiling without memory readings: failing open to the 600s floor")
        }
        return AudioDurationPolicy.ceilingSeconds(path, availableRamBytes, maxHeapBytes)
    }

    /**
     * Metadata pre-read enforcement (spec: the old post-decode check could not
     * enforce a heap-derived ceiling, because the full PCM was already resident
     * when it fired). Reads container duration only, no decode. Missing metadata
     * fails OPEN here; the whole-file path backstops those files post-decode.
     * The caller supplies the already-computed [ceilingSeconds] so one request
     * cannot enforce two different ceilings for the same path.
     */
    private fun validateDuration(
        inputPath: String,
        path: AudioDurationPolicy.DecodePath,
        ceilingSeconds: Long,
    ) {
        val duration = getAudioDuration(inputPath)
        if (duration <= 0.0) return
        if (duration > ceilingSeconds) {
            Log.e(TAG, "Audio too long: ${duration}s > ${ceilingSeconds}s ceiling on $path")
            throw PreprocessingError.DurationTooLong(ceilingSeconds, path)
        }
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
     * Resamples a FloatArray using Kaiser-windowed sinc interpolation.
     * [ratio] = inputRate / targetRate (e.g. 48000/16000 = 3.0).
     *
     * Uses a 16-tap windowed-sinc filter with Kaiser window (beta=5.0) for ~50dB
     * stopband attenuation, suppressing aliasing artifacts that linear interpolation
     * leaves in the 4-8kHz band where ASR models are most sensitive.
     *
     * Coefficients are precomputed into a polyphase table so the inner loop is pure
     * multiply-accumulate with no transcendental function calls. The table comes from
     * [SincResamplerTable], the single definition shared with the streaming resampler.
     *
     * Performance-critical: without pre-resampling, high-rate input (e.g. 48kHz OGG)
     * passes ratio× more samples to sherpa-onnx — on-device benchmarking showed ~2x
     * Parakeet slowdown when this was accidentally removed.
     */
    private fun resampleFloat(input: FloatArray, ratio: Double): FloatArray {
        val outputSize = (input.size / ratio).toInt()
        if (outputSize == 0) return FloatArray(0)
        val output = FloatArray(outputSize)

        val numTaps = SincResamplerTable.NUM_TAPS
        val halfTaps = numTaps / 2
        val table = SincResamplerTable.build(ratio)

        // Inner loop: table lookup + multiply-accumulate only.
        for (i in 0 until outputSize) {
            val srcPos = i * ratio
            val center = srcPos.toInt()
            val frac = srcPos - center
            val phase = (frac * SincResamplerTable.PHASES).toInt().coerceIn(0, SincResamplerTable.PHASES - 1)
            val coeffs = phase * numTaps

            var sum = 0.0
            for (k in 0 until numTaps) {
                val idx = center + k - halfTaps
                if (idx >= 0 && idx < input.size) {
                    sum += input[idx] * table[coeffs + k]
                }
            }
            output[i] = sum.toFloat()
        }

        return output
    }

    /**
     * Modified Bessel function I₀(x). Delegates to the single definition in
     * [SincResamplerTable]; kept as a method because SincResamplerTest accesses
     * it by name via reflection.
     */
    private fun besselI0(x: Double): Double = SincResamplerTable.besselI0(x)

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
