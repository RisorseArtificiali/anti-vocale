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
 * Transcription backend using sherpa-onnx with ONNX Runtime.
 *
 * This backend supports Parakeet TDT and other ONNX-based ASR models.
 * It's typically faster and smaller than multimodal LLM approaches.
 *
 * Key features:
 * - Uses ONNX Runtime (separate from LiteRT-LM's TFLite)
 * - Supports 25 European languages with automatic detection (Parakeet TDT)
 * - ~464MB model size vs ~3.3GB for Gemma 3n
 * - 2.4-2.8x faster transcription
 */
class SherpaOnnxBackend : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "sherpa-onnx"
        private const val TAG = "SherpaOnnxBackend"

        // Required model files for Parakeet TDT (transducer model)
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        )
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Parakeet TDT (sherpa-onnx)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false  // ASR-only, no text generation

    // Parakeet can handle up to 24 minutes in single pass - no chunking needed
    override val maxChunkDurationSeconds: Int? = null

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for SherpaOnnxBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing sherpa-onnx with model dir: $modelDirectory")

        // Validate model directory exists
        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Model directory not found: $modelDirectory"))
        }

        // Validate required model files exist
        val missingFiles = REQUIRED_MODEL_FILES.filter { !File(dir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(
                "Missing model files in $modelDirectory: ${missingFiles.joinToString()}"
            ))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating OfflineRecognizer config...")

                // Configure the transducer model (Parakeet TDT uses transducer architecture)
                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "${modelDirectory}/encoder.int8.onnx",
                        decoder = "${modelDirectory}/decoder.int8.onnx",
                        joiner = "${modelDirectory}/joiner.int8.onnx"
                    ),
                    tokens = "${modelDirectory}/tokens.txt",
                    modelType = sherpaConfig.modelType,
                    numThreads = sherpaConfig.numThreads,
                    debug = false
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 25
                )

                Log.i(TAG, "Creating OfflineRecognizer...")
                recognizer = OfflineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                isInitialized = true

                Log.i(TAG, "sherpa-onnx initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize sherpa-onnx", e)
                Result.failure(e)
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing sherpa-onnx", e)
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
                // WAV format: 44-byte header + 16-bit PCM samples
                val samples = WavUtils.parseWavToFloats(audioData)
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
        // sherpa-onnx is ASR-only, no text generation support
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by sherpa-onnx backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading sherpa-onnx backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: SherpaOnnx backend doesn't manage its own lifecycle
    }

    override fun getModelPath(): String? = modelDir

}
