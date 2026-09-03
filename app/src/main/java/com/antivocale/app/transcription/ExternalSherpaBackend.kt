package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ModelFamily
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single configurable engine for imported external models (spec: external models
 * platform v2a). One [ExternalModelRecord] is configured per [initialize] via
 * [BackendConfig.ExternalConfig]; file names and the sherpa model config come
 * from [ModelFamilySupport.forFamily] (the per-family table the importer also
 * uses, so the two cannot drift).
 *
 * Identity contract: [id] returns the placeholder "external" before the first
 * successful initialize and after [unload]; the backend manager routes the
 * "external:" prefix to this singleton and never registers it under the placeholder,
 * so no consumer can address a half-configured engine.
 */
@Singleton
class ExternalSherpaBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        private const val TAG = "ExternalSherpaBackend"
        private const val PLACEHOLDER_ID = "external"

        /**
         * Family-declared chunk cap (TASK-402 whisper 30s, TASK-408 canary 10s;
         * single-pass families have none). Shared by the engine property and the
         * backend manager's cold gate query (TASK-432) so a record's predicted
         * path can never drift from the loaded engine's behavior.
         */
        fun familyChunkCapSeconds(family: ModelFamily): Int? = when (family) {
            ModelFamily.WHISPER -> 30
            ModelFamily.CANARY -> 10
            else -> null
        }

        fun familyForcesVadAlignedChunking(family: ModelFamily): Boolean =
            family == ModelFamily.CANARY
    }

    @Volatile private var configuredId: String = PLACEHOLDER_ID
    override val id: String get() = configuredId

    @Volatile private var configuredFamily: ModelFamily? = null

    /** Test seam: sets the configured family without the full native init path. */
    @androidx.annotation.VisibleForTesting
    fun configureForTest(record: ExternalModelRecord) {
        configuredFamily = record.family
    }

    override val displayName: String get() = "External model"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    // Family-dependent (TASK-402, adrium's 30s truncation): sherpa's whisper
    // DecodeStream hard-caps a single decode at 30 seconds ("we process only the
    // first 30 seconds and discard the remaining data"), so external WHISPER
    // models must chunk like the built-in one. CANARY (TASK-408) degrades past
    // ~10s (superlinear decode plus repetition), so it chunks shorter AND forces
    // VAD-aligned cuts via requiresVadAlignedChunking: fixed-position cuts make
    // half its chunks decode empty. Transducer/CTC/SenseVoice are encoder-only
    // and genuinely handle any length in one pass (single-pass like
    // Parakeet/GigaAM, the original v2a assumption).
    override val maxChunkDurationSeconds: Int?
        get() = configuredFamily?.let(::familyChunkCapSeconds)

    override val requiresVadAlignedChunking: Boolean
        get() = configuredFamily?.let(::familyForcesVadAlignedChunking) == true

    // @Volatile: a concurrent transcribeAudio on another thread must not read a stale
    // null recognizer after initialize completes (the unload-during-transcription window
    // is inherited from the sibling backends and unchanged).
    @Volatile private var recognizer: OfflineRecognizer? = null
    /** TASK-368: set for streaming records (zipformer transducers); decoded batch-wise. */
    @Volatile private var onlineRecognizer: OnlineRecognizer? = null
    private var modelDir: String? = null
    @Volatile private var isInitialized = false

    /** Idle-unload timer, same rationale as SherpaBackend (TASK-344 / issue #42). */
    private val keepAliveScope =
        // Deliberately hand-built (its Job must be cancellable independently of
        // the shared @ApplicationScope), but carries the CrashReporter handler
        // like every process-lifetime scope (code review 2026-09-03: the keep-alive
        // timer body is uncaught otherwise, and OOM/kill investigations lost these
        // reports). Never cancelled: the timer Job is, resetKeepAliveTimer().
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReporter.handler)
    private val keepAlive = NativeKeepAlive(
        scope = keepAliveScope,
        tag = TAG,
        defaultTimeoutMinutes = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        onIdleUnload = { runCatching { unload() } },
    )
    private val onAutoUnloadCallback = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val externalConfig = config as? BackendConfig.ExternalConfig
            ?: return Result.failure(IllegalArgumentException(
                "Invalid config type for ExternalSherpaBackend (expected ExternalConfig)"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized as $configuredId, returning success")
            return Result.success(Unit)
        }

        val record = externalConfig.record
        val dir = File(record.dir)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: ${record.dir}"))
        }

        // Pre-native validation (inside IO dispatcher): sherpa-onnx calls exit(255)
        // when the family's model file is missing critical metadata, killing the app silently.
        val support = ModelFamilySupport.forFamily(record.family)
        return withContext(Dispatchers.IO) {
            val missing = support.requiredRoles().filterNot { File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "missing files in ${record.dir}: $missing"))
            }

            // Family validation shared with the importer (single definition):
            // [ModelFamilySupport.metadataKeys] plus value-aware discriminators.
            val metadataFile = File(dir, support.metadataFileRole())
            val (missingMeta, metadataValue) = SherpaBackend.missingOnnxMetadataAndValue(
                metadataFile, support.metadataKeys(record.modelType), support.valueMetadataKey())
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "${support.metadataFileRole()} missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt, an incompatible export, or the wrong family. " +
                        "Try re-importing it or correcting its family."))
            }
            try {
                support.validateImportedModel(metadataValue)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Family validation failed for ${record.backendId}: ${e.message}")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "family validation failed for ${record.backendId}: ${e.message ?: "no detail provided"}", e))
            }

            // TASK-368: streaming records build the OnlineRecognizer instead. The
            // family restriction was already enforced at import (entry-JSON choke
            // point); this is the defensive second gate before the native load.
            if (record.streaming && record.family != ModelFamily.TRANSDUCER) {
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "streaming is only supported for TRANSDUCER imports (${record.backendId})"))
            }

            try {
                if (record.streaming) {
                    val transducerConfig = OnlineTransducerModelConfig(
                        encoder = "${record.dir}/${SherpaBackend.CANONICAL_ENCODER}",
                        decoder = "${record.dir}/${SherpaBackend.CANONICAL_DECODER}",
                        joiner = "${record.dir}/${SherpaBackend.CANONICAL_JOINER}",
                    )
                    val onlineConfig = OnlineRecognizerConfig(
                        modelConfig = OnlineModelConfig(
                            transducer = transducerConfig,
                            tokens = "${record.dir}/${SherpaBackend.CANONICAL_TOKENS}",
                            numThreads = externalConfig.numThreads,
                            debug = false,
                            provider = externalConfig.provider,
                            // Streaming zipformers carry no modelType metadata: "" (Nemotron pattern).
                            modelType = "",
                        ),
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    )
                    onlineRecognizer = OnlineRecognizer(config = onlineConfig)
                    modelDir = record.dir
                    configuredId = record.backendId
                    configuredFamily = record.family
                    isInitialized = true
                    keepAlive.start()
                    Log.i(TAG, "External backend initialized (streaming): $configuredId")
                    return@withContext Result.success(Unit)
                }

                val modelConfig = support.buildModelConfig(record, externalConfig.numThreads, externalConfig.provider)

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    // Family-defined mel bands: canary needs 128, every other
                    // family 80 (TASK-408; a wrong dim fails the native load or
                    // decodes garbage).
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = support.featureDim),
                    decodingMethod = "greedy_search"
                )

                recognizer = OfflineRecognizer(config = recognizerConfig)
                modelDir = record.dir
                configuredId = record.backendId
                configuredFamily = record.family
                isInitialized = true
                keepAlive.start()

                Log.i(TAG, "External backend initialized: $configuredId (family=${record.family}, modelType=${record.modelType})")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize external model ${record.backendId}", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing external model ${record.backendId}", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        // beginWork before the recognizer read: closes the idle-unload race
        // (see SherpaBackend for the full rationale).
        keepAlive.beginWork()
        try {
            onlineRecognizer?.let { rec ->
                return withContext(Dispatchers.IO) {
                    transcribeStreamingBatch(rec, samples, sampleRate)
                }
            }
            val rec = recognizer
                ?: return Result.failure(TranscriptionException.NotInitialized())
            return withContext(Dispatchers.IO) {
            // Release the native OfflineStream on EVERY path so the JNI handle is freed
            // deterministically, not left to GC finalization (NemotronStreamingBackend pattern).
            var stream: OfflineStream? = null
            try {
                // Append 1s of silence to improve final token accuracy (Parakeet pattern).
                val silencePad = FloatArray(sampleRate)
                val padded = samples + silencePad

                stream = rec.createStream()
                stream.acceptWaveform(padded, sampleRate)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

                if (transcription.isBlank()) {
                    Result.failure(TranscriptionException.NoTranscriptionProduced())
                } else {
                    // Words-per-second heuristic: keep the original length, not the padded one.
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = detectedLang
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "External transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            } finally {
                stream?.release()
            }
            }
        } finally {
            keepAlive.endWork()
        }
    }

    /**
     * Batch decode over the streaming recognizer (TASK-368): feed the whole clip
     * plus a 1s tail pad, drain the decode loop, signal EOF, drain again (a
     * streaming transducer holds trailing tokens until input-finished), then read
     * the final hypothesis. Same shape as the Nemotron batch path minus the
     * progressive callback (external imports surface one final result).
     */
    private fun transcribeStreamingBatch(
        rec: OnlineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): Result<TranscriptionResult> {
        var stream: OnlineStream? = null
        try {
            stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            stream.acceptWaveform(FloatArray(sampleRate), sampleRate) // tail pad, no per-chunk copy
            while (rec.isReady(stream)) rec.decode(stream)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val transcription = rec.getResult(stream).text
            if (transcription.isBlank()) {
                return Result.failure(TranscriptionException.NoTranscriptionProduced())
            }
            return Result.success(TranscriptionResult(
                text = transcription,
                confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate),
            ))
        } catch (e: Exception) {
            Log.e(TAG, "External streaming transcription failed", e)
            return Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
        } finally {
            stream?.release()
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by the external sherpa engine. Use for audio transcription only."))
    }

    override fun isReady(): Boolean = isInitialized && (recognizer != null || onlineRecognizer != null)

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading external backend: $configuredId")
        keepAlive.stop()
        recognizer?.release()
        recognizer = null
        onlineRecognizer?.release()
        onlineRecognizer = null
        modelDir = null
        isInitialized = false
        configuredId = PLACEHOLDER_ID
        configuredFamily = null
        onAutoUnloadCallback.get()?.invoke()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        keepAlive.setTimeout(minutes)
    }

    override fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {
        onAutoUnloadCallback.set(callback)
    }

    override fun getModelPath(): String? = modelDir
}
