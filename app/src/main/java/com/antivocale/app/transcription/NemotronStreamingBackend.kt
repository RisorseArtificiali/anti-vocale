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
 * Transcription backend for the Nemotron 3.5 streaming ASR model.
 *
 * Nemotron is a cache-aware streaming transducer, so it MUST use sherpa-onnx's
 * [OnlineRecognizer] (streaming) classes rather than the Offline classes used by
 * [SherpaOnnxBackend] / [Qwen3AsrBackend]. However, this backend still exposes the
 * standard batch [TranscriptionBackend] interface — the orchestrator already handles
 * progressive display via VAD segmentation, so no streaming Flows/partials are added.
 *
 * Batch-via-online transcription: a single [OnlineStream] is created, the entire
 * `samples` array is accepted via [OnlineStream.acceptWaveform], the
 * `while (isReady) decode` loop runs to completion, [OnlineStream.inputFinished]
 * is signaled, a final [OnlineRecognizer.decode] is issued, and the text is read
 * from [OnlineRecognizer.getResult].
 *
 * Language: the model runs in multilingual auto-detect (no explicit language passed).
 * sherpa v1.13.3's per-stream language API is a separate feature (NOT the createStream
 * hotwords arg); wiring per-language selection is a documented follow-up.
 */
@Singleton
class NemotronStreamingBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "nemotron-streaming"
        private const val TAG = "NemotronStreamingBackend"
        // Trailing silence appended so the 1120ms-chunk streaming encoder has a complete final
        // chunk to flush trailing tokens. Without it, clips ending mid-chunk truncate the tail.
        private const val TAIL_PADDING_SECONDS = 1.5
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Nemotron 3.5 (streaming)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    // Streaming transducer handles long audio in a single pass — no chunking limit.
    override val maxChunkDurationSeconds: Int? = null

    private var recognizer: OnlineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false
    /** Per-stream language for Nemotron multilingual ("auto" or a code like "it"/"en"). AC #3. */
    private var language: String = "auto"

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for NemotronStreamingBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing Nemotron streaming backend with model dir: $modelDirectory")

        // Validate the model directory via NemotronModelManager (mirrors SherpaOnnxBackend
        // discovery via its model manager). This checks all required files
        // (encoder.onnx, encoder.onnx.data, decoder.onnx, joiner.onnx, tokens.txt).
        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IllegalArgumentException("Model directory not found: $modelDirectory"))
        }
        if (NemotronModelManager.validateModelDirectory(dir) == null) {
            return Result.failure(IllegalArgumentException(
                "Missing or incomplete Nemotron model files in $modelDirectory. " +
                    "Required: ${NemotronModelManager.REQUIRED_FILES.joinToString()}"
            ))
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating OnlineRecognizer config...")

                // Nemotron ships as int8 (encoder/decoder/joiner.int8.onnx). Per sherpa's own
                // OnlineRecognizer.kt Nemotron cases, it uses OnlineTransducerModelConfig with an
                // EMPTY modelType — NOT "nemo_transducer" (that's for offline Parakeet).
                val transducerConfig = OnlineTransducerModelConfig(
                    encoder = "$modelDirectory/encoder.int8.onnx",
                    decoder = "$modelDirectory/decoder.int8.onnx",
                    joiner = "$modelDirectory/joiner.int8.onnx"
                )

                val modelConfig = OnlineModelConfig(
                    transducer = transducerConfig,
                    tokens = "$modelDirectory/tokens.txt",
                    numThreads = sherpaConfig.numThreads,
                    debug = false,
                    provider = sherpaConfig.provider,
                    modelType = "" // Nemotron online: empty modelType (do NOT use nemo_transducer)
                )

                val recognizerConfig = OnlineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    )
                )

                Log.i(TAG, "Creating OnlineRecognizer...")
                recognizer = OnlineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                language = sherpaConfig.language.ifBlank { "auto" }
                isInitialized = true

                Log.i(TAG, "Nemotron streaming backend initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Nemotron streaming backend", e)
                Result.failure(e)
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing Nemotron streaming backend", e)
                Result.failure(IllegalStateException("Native error: ${e.message}"))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> =
        transcribeAudioStreaming(samples, sampleRate, prompt) { /* batch interface: no progressive partials */ }

    override suspend fun transcribeAudioStreaming(
        samples: FloatArray,
        sampleRate: Int,
        prompt: String,
        onPartial: suspend (String) -> Unit
    ): Result<TranscriptionResult> {
        val rec = recognizer
            ?: return Result.failure(IllegalStateException("Backend not initialized"))

        return withContext(Dispatchers.IO) {
            var stream: OnlineStream? = null
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                // Feed the whole clip up front (identical to batch-via-online, so the final
                // result is unchanged), then emit the recognizer's growing hypothesis after
                // each decode() pass for progressive display.
                // createStream(String) arg is HOTWORDS/contextual biasing, NOT language — passing a
                // non-empty value (e.g. "auto") triggers contextual biasing, which sherpa aborts on
                // (exit 255). Empty = no biasing; language is set via setOption below.
                stream = rec.createStream("")
                // AC #3: condition the multilingual model on the user's language ("auto" = auto-detect,
                // else a specific code). This is OnlineStream.setOption — distinct from createStream's
                // hotwords arg; passing a language via createStream triggers contextual biasing (exit 255).
                stream.setOption("language", language)
                // Tail padding (TASK-296): feed a zero-padded copy to the recognizer so the
                // streaming encoder gets a complete final chunk to flush trailing tokens. Keep the
                // original samples length for the confidence/duration calc below.
                val tailPadSamples = (TAIL_PADDING_SECONDS * sampleRate).toInt()
                val recognizerInput =
                    if (tailPadSamples > 0) samples.copyOf(samples.size + tailPadSamples) else samples
                stream.acceptWaveform(recognizerInput, sampleRate)

                // Decode loop: drain the recognizer's internal buffer, emitting the growing
                // hypothesis after each pass so the UI can render text progressively.
                var lastEmitted = ""
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                    val partial = rec.getResult(stream).text
                    if (partial.isNotBlank() && partial != lastEmitted) {
                        onPartial(partial)
                        lastEmitted = partial
                    }
                }

                stream.inputFinished()
                // Drain trailing hypotheses after end-of-input. A streaming transducer can hold
                // several final tokens until EOF; a single decode pass may not flush them all,
                // which truncated the transcript's tail (TASK-296). Loop until nothing remains.
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val result = rec.getResult(stream)
                val transcription = result.text
                // Emit the final hypothesis so the UI shows the complete text (may differ from
                // the last partial if the end-of-input decode produced trailing tokens).
                if (transcription.isNotBlank() && transcription != lastEmitted) {
                    onPartial(transcription)
                }

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(IllegalStateException("No transcription produced"))
                } else {
                    // OnlineRecognizerResult exposes no confidence/language fields,
                    // so derive a heuristic confidence and leave detectedLanguage null.
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = null
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(e)
            } finally {
                // Release the native OnlineStream on EVERY path (happy, exception, blank-result)
                // so the JNI handle is freed deterministically, not left to GC finalization.
                stream?.release()
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        // Nemotron is ASR-only, no text generation support.
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by Nemotron streaming backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading Nemotron streaming backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        language = "auto"
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: Nemotron streaming backend doesn't manage its own lifecycle
    }

    override fun getModelPath(): String? = modelDir
}
