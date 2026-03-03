package com.localai.bridge.transcription

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcription backend using sherpa-onnx with Whisper models.
 *
 * This backend provides excellent multilingual transcription with proper
 * punctuation, making it ideal for languages like Italian that need
 * accurate text formatting.
 *
 * Key features:
 * - Uses ONNX Runtime (same as Parakeet, separate from LiteRT-LM's TFLite)
 * - Excellent multilingual support with proper punctuation
 * - ~75MB (Tiny) or ~150MB (Base) model sizes
 * - Slower than Parakeet but more accurate for non-English languages
 */
class WhisperBackend : TranscriptionBackend {

    companion object {
        private const val TAG = "WhisperBackend"
        private const val DEFAULT_KEEP_ALIVE_MINUTES = 5

        // Required model files for Whisper (separate encoder and decoder)
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",  // Encoder model
            "decoder.int8.onnx",  // Decoder model
            "tokens.txt"          // Vocabulary tokens
        )
    }

    override val id: String = "whisper"
    override val displayName: String = "Whisper (sherpa-onnx)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false  // ASR-only, no text generation

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false
    private var keepAliveTimeoutMinutes: Int = DEFAULT_KEEP_ALIVE_MINUTES

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for WhisperBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing Whisper with model dir: $modelDirectory")

        // Validate model directory exists
        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Model directory not found: $modelDirectory"))
        }

        // Discover model files (handle different naming conventions)
        val modelFiles = discoverModelFiles(dir)
        if (modelFiles == null) {
            return Result.failure(IllegalArgumentException(
                "Missing model files in $modelDirectory. Need encoder.onnx, decoder.onnx, and tokens.txt"
            ))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating OfflineRecognizer config for Whisper...")

                // Configure Whisper model
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = modelFiles.encoderPath,
                    decoder = modelFiles.decoderPath,
                    language = "",  // Auto-detect language for multilingual models
                    task = "transcribe",  // Transcribe (not translate)
                    tailPaddings = 1000
                )

                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = modelFiles.tokensPath,
                    modelType = "whisper",
                    numThreads = 4,
                    debug = false
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    decodingMethod = "greedy_search"
                )

                Log.i(TAG, "Creating OfflineRecognizer for Whisper...")
                recognizer = OfflineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                isInitialized = true

                Log.i(TAG, "Whisper backend initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Whisper", e)
                Result.failure(e)
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing Whisper", e)
                Result.failure(IllegalStateException("Native error: ${e.message}"))
            }
        }
    }

    override suspend fun transcribeAudio(audioData: ByteArray, prompt: String): Result<String> {
        val rec = recognizer
            ?: return Result.failure(IllegalStateException("Backend not initialized"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Transcribing audio: ${audioData.size} bytes")

                // Convert WAV ByteArray to float samples
                val samples = parseWavToFloats(audioData)
                Log.d(TAG, "Parsed ${samples.size} audio samples")

                // Create stream and process audio
                val stream = rec.createStream()
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)

                // Get result
                val result = rec.getResult(stream)
                val transcription = result.text

                // Release stream
                stream.release()

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(IllegalStateException("No transcription produced"))
                } else {
                    Result.success(transcription)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        // Whisper is ASR-only, no text generation support
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by Whisper backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading Whisper backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        keepAliveTimeoutMinutes = if (minutes > 0) minutes else DEFAULT_KEEP_ALIVE_MINUTES
        Log.d(TAG, "Keep-alive timeout set to $keepAliveTimeoutMinutes minutes")
    }

    override fun getModelPath(): String? = modelDir

    /**
     * Discovers model files in the directory, handling different naming conventions.
     */
    private data class ModelFiles(
        val encoderPath: String,
        val decoderPath: String,
        val tokensPath: String
    )

    private fun discoverModelFiles(dir: File): ModelFiles? {
        // Find tokens.txt (required) - try various naming conventions
        val tokensPatterns = listOf(
            "tokens.txt",
            "tiny.en-tokens.txt",
            "base.en-tokens.txt",
            "small.en-tokens.txt",
            "tiny-tokens.txt",
            "base-tokens.txt"
        )

        val tokensFile = tokensPatterns
            .map { File(dir, it) }
            .firstOrNull { it.exists() }

        if (tokensFile == null) {
            Log.d(TAG, "tokens.txt not found in ${dir.absolutePath}")
            return null
        }

        // Find encoder file (try various naming conventions)
        val encoderPatterns = listOf(
            "encoder.int8.onnx",
            "tiny.en-encoder.int8.onnx",
            "base.en-encoder.int8.onnx",
            "small.en-encoder.int8.onnx",
            "tiny-encoder.int8.onnx",
            "base-encoder.int8.onnx"
        )

        val encoderFile = encoderPatterns
            .map { File(dir, it) }
            .firstOrNull { it.exists() }

        if (encoderFile == null) {
            Log.d(TAG, "Encoder file not found in ${dir.absolutePath}")
            return null
        }

        // Find decoder file (try various naming conventions)
        val decoderPatterns = listOf(
            "decoder.int8.onnx",
            "tiny.en-decoder.int8.onnx",
            "base.en-decoder.int8.onnx",
            "small.en-decoder.int8.onnx",
            "tiny-decoder.int8.onnx",
            "base-decoder.int8.onnx"
        )

        val decoderFile = decoderPatterns
            .map { File(dir, it) }
            .firstOrNull { it.exists() }

        if (decoderFile == null) {
            Log.d(TAG, "Decoder file not found in ${dir.absolutePath}")
            return null
        }

        Log.i(TAG, "Found Whisper model files: encoder=${encoderFile.name}, decoder=${decoderFile.name}, tokens=${tokensFile.name}")

        return ModelFiles(
            encoderPath = encoderFile.absolutePath,
            decoderPath = decoderFile.absolutePath,
            tokensPath = tokensFile.absolutePath
        )
    }

    /**
     * Parses WAV ByteArray to FloatArray samples.
     *
     * Assumes WAV format with 44-byte header and 16-bit signed PCM samples.
     * Converts to normalized float values in range [-1.0, 1.0].
     */
    private fun parseWavToFloats(wavData: ByteArray): FloatArray {
        // Skip 44-byte WAV header
        val headerSize = 44
        if (wavData.size <= headerSize) {
            throw IllegalArgumentException("WAV data too short: ${wavData.size} bytes")
        }

        // Calculate number of samples (16-bit = 2 bytes per sample)
        val numSamples = (wavData.size - headerSize) / 2
        val samples = FloatArray(numSamples)

        // Wrap byte array with ByteBuffer for efficient reading
        val buffer = ByteBuffer.wrap(wavData, headerSize, wavData.size - headerSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Convert 16-bit signed PCM to float in range [-1.0, 1.0]
        for (i in 0 until numSamples) {
            val shortValue = buffer.short
            samples[i] = shortValue / 32768.0f
        }

        return samples
    }
}
