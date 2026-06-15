package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcription backend using sherpa-onnx with Meta's Omnilingual ASR v2 (CTC) models,
 * covering 1600+ languages.
 *
 * CTC architecture — only an encoder ONNX + tokens.txt are needed (no decoder/joiner).
 * Output is character-level and usually lacks punctuation/casing, so this is positioned as a
 * fallback for exotic languages not covered by Parakeet/Whisper, not a primary backend.
 *
 * Key points:
 * - Uses ONNX Runtime (same as Whisper/Qwen3, separate from LiteRT-LM's TFLite)
 * - No language parameter (single multilingual model)
 * - Chunking capped at 35s to stay under the model's 40s input limit
 */
@Singleton
class OmnilingualAsrBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "omnilingual-asr"
        private const val TAG = "OmnilingualAsrBackend"

        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "tokens.txt"
        )
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Omnilingual ASR (1600+ languages)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    /** CTC variant has a ~40s input ceiling; keep chunks safely under it. */
    override val maxChunkDurationSeconds: Int = 35

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for OmnilingualAsrBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing Omnilingual ASR with model dir: $modelDirectory")

        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Model directory not found: $modelDirectory"))
        }

        val modelFiles = discoverModelFiles(dir)
        if (modelFiles == null) {
            return Result.failure(IllegalArgumentException(
                "Missing model files in $modelDirectory. Need encoder.int8.onnx and tokens.txt"
            ))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating OfflineRecognizer config for Omnilingual ASR...")

                val omnilingualConfig = OfflineOmnilingualAsrCtcModelConfig(
                    model = modelFiles.encoderPath
                )

                val modelConfig = OfflineModelConfig(
                    omnilingual = omnilingualConfig,
                    tokens = modelFiles.tokensPath,
                    numThreads = sherpaConfig.numThreads,
                    debug = false,
                    provider = sherpaConfig.provider
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    decodingMethod = "greedy_search"
                )

                Log.i(TAG, "Creating OfflineRecognizer for Omnilingual ASR...")
                recognizer = OfflineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                isInitialized = true

                Log.i(TAG, "Omnilingual ASR backend initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Omnilingual ASR", e)
                Result.failure(e)
            } catch (e: Error) {
                Log.e(TAG, "Native error initializing Omnilingual ASR", e)
                Result.failure(IllegalStateException("Native error: ${e.message}"))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val rec = recognizer
            ?: return Result.failure(IllegalStateException("Backend not initialized"))

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                val stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                // CTC output may not expose a detected language.
                val detectedLang = result.lang.ifBlank { null }

                stream.release()

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(IllegalStateException("No transcription produced"))
                } else {
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = detectedLang
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by Omnilingual ASR backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading Omnilingual ASR backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {}

    override fun getModelPath(): String? = modelDir

    private data class ModelFiles(
        val encoderPath: String,
        val tokensPath: String
    )

    private fun discoverModelFiles(dir: File): ModelFiles? {
        val model = OmnilingualAsrModelManager.validateModelDirectory(dir) ?: return null
        Log.i(TAG, "Found Omnilingual ASR model files in ${dir.absolutePath}")
        return ModelFiles(
            encoderPath = model.encoderPath,
            tokensPath = model.tokensPath
        )
    }
}
