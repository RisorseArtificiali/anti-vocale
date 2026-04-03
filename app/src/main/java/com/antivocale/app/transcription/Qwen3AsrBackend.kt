package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.util.WavUtils
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcription backend using sherpa-onnx with Qwen3-ASR models.
 *
 * This backend provides automatic language detection across 52 languages,
 * making it ideal for multilingual use cases without manual language selection.
 *
 * Key features:
 * - Uses ONNX Runtime (same as Whisper, separate from LiteRT-LM's TFLite)
 * - Automatic language detection (52 languages)
 * - No language parameter needed
 * - Separate conv_frontend, encoder, decoder, and tokenizer directory
 */
class Qwen3AsrBackend : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "qwen3-asr"
        private const val TAG = "Qwen3AsrBackend"

        val REQUIRED_MODEL_FILES = listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/"
        )
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Qwen3-ASR (52 languages)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for Qwen3AsrBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing Qwen3-ASR with model dir: $modelDirectory")

        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Model directory not found: $modelDirectory"))
        }

        val modelFiles = discoverModelFiles(dir)
        if (modelFiles == null) {
            return Result.failure(IllegalArgumentException(
                "Missing model files in $modelDirectory. Need conv_frontend.onnx, encoder.int8.onnx, decoder.int8.onnx, and tokenizer/ directory"
            ))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating OfflineRecognizer config for Qwen3-ASR...")

                val qwen3Config = OfflineQwen3AsrModelConfig(
                    convFrontend = modelFiles.convFrontendPath,
                    encoder = modelFiles.encoderPath,
                    decoder = modelFiles.decoderPath,
                    tokenizer = modelFiles.tokenizerDirPath,
                    maxNewTokens = 2048
                )

                val modelConfig = OfflineModelConfig(
                    qwen3Asr = qwen3Config,
                    modelType = "qwen3_asr",
                    numThreads = sherpaConfig.numThreads,
                    debug = false
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    decodingMethod = "greedy_search",
                    blankPenalty = 1.0f
                )

                Log.i(TAG, "Creating OfflineRecognizer for Qwen3-ASR...")
                recognizer = OfflineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                isInitialized = true

                Log.i(TAG, "Qwen3-ASR backend initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Qwen3-ASR", e)
                Result.failure(e)
            } catch (e: Error) {
                Log.e(TAG, "Native error initializing Qwen3-ASR", e)
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

                val samples = WavUtils.parseWavToFloats(audioData)
                Log.d(TAG, "Parsed ${samples.size} audio samples, duration: ${samples.size / 16000.0f}s")

                val stream = rec.createStream()
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text

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
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by Qwen3-ASR backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading Qwen3-ASR backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {}

    override fun getModelPath(): String? = modelDir

    private data class ModelFiles(
        val convFrontendPath: String,
        val encoderPath: String,
        val decoderPath: String,
        val tokenizerDirPath: String
    )

    private fun discoverModelFiles(dir: File): ModelFiles? {
        val model = Qwen3AsrModelManager.validateModelDirectory(dir) ?: return null
        Log.i(TAG, "Found Qwen3-ASR model files in ${dir.absolutePath}")
        return ModelFiles(
            convFrontendPath = model.convFrontendPath,
            encoderPath = model.encoderPath,
            decoderPath = model.decoderPath,
            tokenizerDirPath = model.tokenizerDirPath
        )
    }
}
