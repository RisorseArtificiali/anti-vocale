package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.util.WavUtils
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
 * - ~110MB (Tiny) or ~197MB (Base) model sizes
 * - Slower than Parakeet but more accurate for non-English languages
 */
class WhisperBackend : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "whisper"
        private const val TAG = "WhisperBackend"

        // Required model files for Whisper (separate encoder and decoder)
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",  // Encoder model
            "decoder.int8.onnx",  // Decoder model
            "tokens.txt"          // Vocabulary tokens
        )
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Whisper (sherpa-onnx)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false  // ASR-only, no text generation

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

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
                // Distil Italian model only supports Italian, so ignore user language preference
                val isDistil = modelDirectory.contains("distil")
                val language = if (isDistil) "it" else sherpaConfig.language
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = modelFiles.encoderPath,
                    decoder = modelFiles.decoderPath,
                    language = language,
                    task = "transcribe",
                    tailPaddings = 1000
                )

                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = modelFiles.tokensPath,
                    modelType = "whisper",
                    numThreads = sherpaConfig.numThreads,
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
                val samples = WavUtils.parseWavToFloats(audioData)
                Log.d(TAG, "Parsed ${samples.size} audio samples, duration: ${samples.size / 16000.0f}s")

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
        // No-op: Whisper backend doesn't manage its own lifecycle
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
        val model = WhisperModelManager.validateModelDirectory(dir) ?: return null
        val encoderPath = model.encoderPath
            ?: run {
                Log.d(TAG, "No separate encoder file found in ${dir.absolutePath} (combined encoder-decoder not supported by this backend)")
                return null
            }
        val decoderPath = model.decoderPath
            ?: run {
                Log.d(TAG, "No separate decoder file found in ${dir.absolutePath}")
                return null
            }
        Log.i(TAG, "Found Whisper model files: encoder=${File(encoderPath).name}, decoder=${File(decoderPath).name}, tokens=${File(model.tokensPath!!).name}")
        return ModelFiles(
            encoderPath = encoderPath,
            decoderPath = decoderPath,
            tokensPath = model.tokensPath!!
        )
    }
}
