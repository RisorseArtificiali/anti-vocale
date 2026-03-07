package com.antivocale.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles audio preprocessing for MediaPipe's Gemma 3n multimodal model.
 *
 * Converts audio files into 16kHz mono WAV ByteArrays using Android's
 * built-in MediaCodec and MediaExtractor APIs.
 *
 * Note: FFmpegKit was retired in January 2025. This implementation uses
 * native Android APIs which are more reliable and don't require external dependencies.
 */
object AudioPreprocessor {

    private const val TAG = "AudioPreprocessor"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1
    private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB limit
    private const val MAX_DURATION_SECONDS = 600 // 10 minutes max
    private const val TIMEOUT_US = 10000L

    /**
     * Result of audio preprocessing containing one or more WAV chunks.
     */
    data class PreprocessingResult(
        val chunks: List<ByteArray>,
        val totalDurationSeconds: Double,
        val chunkCount: Int
    )

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
        maxChunkDurationSeconds: Int? = 30
    ): PreprocessingResult {
        Log.d(TAG, "Preparing audio: $inputPath")

        // Validate input file exists
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file does not exist: $inputPath")
            throw PreprocessingError.FileNotFound
        }

        // Check file size
        val fileSize = inputFile.length()
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            Log.e(TAG, "File too large: $fileSize bytes")
            throw PreprocessingError.FileTooLarge
        }

        if (fileSize == 0L) {
            Log.e(TAG, "File is empty")
            throw PreprocessingError.InvalidFormat
        }

        // Extract and resample audio
        val pcmData = extractAndResampleAudio(inputPath)

        // Get duration
        val duration = pcmData.size.toDouble() / TARGET_SAMPLE_RATE / 2 // 16-bit = 2 bytes

        if (duration > MAX_DURATION_SECONDS) {
            Log.e(TAG, "Audio too long: ${duration}s")
            throw PreprocessingError.DurationTooLong
        }

        Log.d(TAG, "Audio duration: ${duration}s")

        // Chunk if necessary
        if (maxChunkDurationSeconds == null || duration <= maxChunkDurationSeconds) {
            // No chunking needed - return single chunk
            val wavBytes = createWavByteArray(pcmData)
            return PreprocessingResult(
                chunks = listOf(wavBytes),
                totalDurationSeconds = duration,
                chunkCount = 1
            )
        } else {
            // Chunk into segments
            return chunkAudio(pcmData, duration, maxChunkDurationSeconds)
        }
    }

    /**
     * Extracts audio from file and resamples to 16kHz mono PCM.
     */
    private fun extractAndResampleAudio(inputPath: String): ByteArray {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(inputPath)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                throw PreprocessingError.NoAudioTrack
            }

            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            extractor.selectTrack(audioTrackIndex)

            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            Log.d(TAG, "Input: $mime, ${inputSampleRate}Hz, $inputChannels channels")

            // Create decoder
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Create resampler/encoder for output
            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_RAW,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_SAMPLE_RATE * TARGET_CHANNELS * 16)
            }

            val pcmData = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false

            // Resampling ratio
            val resampleRatio = TARGET_SAMPLE_RATE.toDouble() / inputSampleRate

            while (!outputEOS) {
                // Feed input to decoder
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
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                presentationTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Get decoded output
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Read PCM data
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputBuffer.clear()

                        // Convert to mono if stereo
                        val monoData = if (inputChannels == 2) {
                            stereoToMono(chunk)
                        } else {
                            chunk
                        }

                        // Resample if needed
                        val resampledData = if (inputSampleRate != TARGET_SAMPLE_RATE) {
                            resampleAudio(monoData, resampleRatio)
                        } else {
                            monoData
                        }

                        pcmData.write(resampledData)
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Output format changed: ${decoder.outputFormat}")
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            val result = pcmData.toByteArray()
            Log.d(TAG, "Extracted ${result.size} bytes of PCM audio")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio", e)
            throw PreprocessingError.ConversionFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Converts stereo PCM to mono by averaging channels.
     */
    private fun stereoToMono(stereoData: ByteArray): ByteArray {
        val monoSamples = stereoData.size / 4 // 2 bytes per sample, 2 channels
        val monoData = ByteArray(monoSamples * 2)

        for (i in 0 until monoSamples) {
            val leftSample = ByteBuffer.wrap(stereoData, i * 4, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short
            val rightSample = ByteBuffer.wrap(stereoData, i * 4 + 2, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short

            val monoSample = ((leftSample.toInt() + rightSample.toInt()) / 2).toShort()

            ByteBuffer.wrap(monoData, i * 2, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(monoSample)
        }

        return monoData
    }

    /**
     * Simple linear interpolation resampling.
     */
    private fun resampleAudio(inputData: ByteArray, ratio: Double): ByteArray {
        val inputSamples = inputData.size / 2 // 16-bit samples
        val outputSamples = (inputSamples * ratio).toInt()
        val outputData = ByteArray(outputSamples * 2)

        for (i in 0 until outputSamples) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()

            if (srcIndex < inputSamples - 1) {
                // Linear interpolation
                val frac = srcPos - srcIndex

                val sample1 = ByteBuffer.wrap(inputData, srcIndex * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short
                val sample2 = ByteBuffer.wrap(inputData, (srcIndex + 1) * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short

                val interpolatedSample = (sample1 * (1 - frac) + sample2 * frac).toInt().toShort()

                ByteBuffer.wrap(outputData, i * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(interpolatedSample)
            } else if (srcIndex < inputSamples) {
                // Last sample
                val sample = ByteBuffer.wrap(inputData, srcIndex * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short
                ByteBuffer.wrap(outputData, i * 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(sample)
            }
        }

        return outputData
    }

    /**
     * Creates a WAV file byte array from PCM data.
     */
    private fun createWavByteArray(pcmData: ByteArray): ByteArray {
        val wavData = ByteArrayOutputStream()

        // RIFF header
        wavData.write("RIFF".toByteArray())
        wavData.write(intToLittleEndian(36 + pcmData.size))
        wavData.write("WAVE".toByteArray())

        // fmt chunk
        wavData.write("fmt ".toByteArray())
        wavData.write(intToLittleEndian(16)) // Chunk size
        wavData.write(shortToLittleEndian(1)) // Audio format (PCM)
        wavData.write(shortToLittleEndian(TARGET_CHANNELS))
        wavData.write(intToLittleEndian(TARGET_SAMPLE_RATE))
        wavData.write(intToLittleEndian(TARGET_SAMPLE_RATE * TARGET_CHANNELS * 2)) // Byte rate
        wavData.write(shortToLittleEndian(TARGET_CHANNELS * 2)) // Block align
        wavData.write(shortToLittleEndian(16)) // Bits per sample

        // data chunk
        wavData.write("data".toByteArray())
        wavData.write(intToLittleEndian(pcmData.size))
        wavData.write(pcmData)

        return wavData.toByteArray()
    }

    /**
     * Chunks audio data into segments of specified duration.
     */
    private fun chunkAudio(pcmData: ByteArray, duration: Double, maxChunkDurationSeconds: Int): PreprocessingResult {
        val samplesPerChunk = TARGET_SAMPLE_RATE * maxChunkDurationSeconds * 2 // 16-bit = 2 bytes
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var chunkIndex = 0

        while (offset < pcmData.size) {
            val chunkSize = minOf(samplesPerChunk.toInt(), pcmData.size - offset)
            val chunkData = pcmData.copyOfRange(offset, offset + chunkSize)
            val wavChunk = createWavByteArray(chunkData)
            chunks.add(wavChunk)

            Log.d(TAG, "Created chunk $chunkIndex: ${chunkSize / 2} samples")

            offset += chunkSize
            chunkIndex++
        }

        return PreprocessingResult(
            chunks = chunks,
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
