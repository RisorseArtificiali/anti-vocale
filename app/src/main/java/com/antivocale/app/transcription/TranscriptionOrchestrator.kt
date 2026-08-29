package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.audio.AudioPreprocessor.StreamEvent
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogStringKeys
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.service.ExtractionService
import com.antivocale.app.service.TranscriptionListener
import com.antivocale.app.ui.viewmodel.LogEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure business logic for transcription orchestration.
 *
 * Owns queue processing, backend loading, audio preprocessing,
 * transcription, calibration, and DB logging. Communicates results
 * and progress back to the Android service layer via [TranscriptionListener].
 */
@Singleton
class TranscriptionOrchestrator @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val logDao: LogDao,
    private val transcriptionCalibrator: TranscriptionCalibrator,
    private val backendManager: TranscriptionBackendManager,
    private val audioPreprocessor: AudioPreprocessor,
    private val backendRegistry: BackendRegistry,
    private val externalModelStore: ExternalModelStore,
) {
    companion object {
        private const val TAG = "TranscriptionOrchestrator"
        private const val MAX_CONCURRENT_CHUNKS = 2
        private const val PARTIAL_SAVE_INTERVAL_MS = 5000L
        private const val MB = 1024L * 1024L
        // Headroom over the on-disk model size: absorbs sherpa inference buffers and reclaimable-cache
        // noise in availMem. Tunable; see TASK-314 spec. ~300MB derived from the SmoothQuant incident.
        private const val MEMORY_HEADROOM_BYTES = 300L * MB

        /** Backend id of the disabled GGUF backend; deliberately unregistered in [BackendRegistry]. */
        private const val GGUF_BACKEND_ID = "gemma4_gguf"

        internal fun isNoModelConfiguredError(error: Throwable): Boolean {
            return error is TranscriptionException.NotInitialized ||
                // A dangling external id is the same UX class: no usable model configured.
                error is TranscriptionException.ExternalModelUnavailable
        }

        /**
         * Maps a [TranscriptionException] to a user-facing localized message via the given [context].
         * Non-TranscriptionException errors fall back to the generic [R.string.transcription_failed].
         */
        internal fun userFacingErrorMessage(context: Context, error: Throwable): String {
            return when (error) {
                is TranscriptionException.ModelLoadError ->
                    context.getString(R.string.error_model_load)
                is TranscriptionException.InsufficientMemory ->
                    // The exception already carries the localized low-memory message with the
                    // measured numbers; surface it directly instead of the generic model-load string.
                    error.message ?: context.getString(R.string.error_model_load)
                is TranscriptionException.NativeError ->
                    context.getString(R.string.error_native)
                is TranscriptionException.NotInitialized ->
                    context.getString(R.string.error_not_initialized)
                is TranscriptionException.ExternalModelUnavailable ->
                    // A dangling external: id must not read as the generic corrupt-model
                    // message; the user just needs to pick another model (TASK-342).
                    context.getString(R.string.error_model_unavailable)
                is TranscriptionException.NoTranscriptionProduced ->
                    context.getString(R.string.transcription_failed)
                else -> context.getString(R.string.transcription_failed)
            }
        }
    }

    @Volatile
    private var lastPartialSaveMs: Long = 0L

    /** Per-task timestamp of the last interim Room write (throttle, TASK-340 Fix 2b). */
    private val lastInterimRoomWriteMs = mutableMapOf<String, Long>()

    /**
     * Clock read ONLY by the interim-write throttle. Injectable so the throttle is
     * unit-testable; mocking System.currentTimeMillis statically is not viable (mockk
     * intercepts JVM-internal calls and recurses forever).
     */
    internal var throttleClock: () -> Long = System::currentTimeMillis

    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    /**
     * Processes a single transcription request.
     * All Android-specific side effects are delegated to [listener].
     *
     * @param context Android context needed for backend initialization (not stored)
     * @param cacheDir Cache directory for audio preprocessing (not stored)
     * @param coroutineScope Scope for launching background work (progress timer, calibration)
     */
    suspend fun processRequest(
        taskId: String,
        requestType: String,
        prompt: String = "",
        filePath: String?,
        source: String?,
        sourcePackage: String?,
        backendOverride: String? = null,
        trackIndex: Int = -1,
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope
    ): Result<String> {
        val isShareRequest = source == "share"

        // Log request start
        com.antivocale.app.util.DiagTrace.mark("orch-markProcessing-db", "task=$taskId")
        markProcessing(taskId)
        com.antivocale.app.util.DiagTrace.mark("orch-request-begin", "task=$taskId type=$requestType share=$isShareRequest trackIndex=$trackIndex")

        val startTime = System.currentTimeMillis()

        try {
            // Subtitle mode: extract embedded text subtitles WITHOUT loading any model. On
            // extraction failure (null/blank), fall back to the normal audio/ASR path below
            // rather than reporting an error. This branch must run BEFORE ensureBackendLoaded
            // so a missing model never blocks a subtitle hit.
            if (requestType == "subtitles") {
                val subtitleResult = processSubtitleRequest(
                    taskId = taskId,
                    filePath = filePath,
                    trackIndex = trackIndex,
                    source = source,
                    sourcePackage = sourcePackage,
                    isShareRequest = isShareRequest,
                    startTime = startTime,
                    context = context,
                    cacheDir = cacheDir,
                    listener = listener,
                    coroutineScope = coroutineScope,
                    queuePosition = queuePosition,
                    queueTotal = queueTotal,
                    prompt = prompt,
                    backendOverride = backendOverride
                )
                // Non-null result = the subtitle path resolved the request (success OR a
                // fallback-driven error already reported to the listener). Null = extraction
                // yielded nothing and the caller should run ASR — handled below.
                if (subtitleResult != null) return subtitleResult
            }

            // Ensure the correct backend is loaded
            com.antivocale.app.util.DiagTrace.mark("orch-ensureBackend-begin", "backendOverride=$backendOverride")
            val loadResult = ensureBackendLoaded(context, backendOverride)
            com.antivocale.app.util.DiagTrace.mark(
                "orch-ensureBackend-done",
                if (loadResult.isSuccess) "ok" else "FAILED: ${loadResult.exceptionOrNull()?.message}"
            )
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()!!
                val userMsg = userFacingErrorMessage(context, error)
                val logMsg = "Failed to load backend: ${error.message}"
                val duration = System.currentTimeMillis() - startTime
                val isNoModel = isNoModelConfiguredError(error)
                logError(taskId, logMsg, duration)
                listener.onError(taskId, "BACKEND_LOAD_FAILED", userMsg, isShareRequest, isNoModel, duration)
                return Result.failure(error)
            }

            // GH #45: record which model handled the request, as soon as the
            // backend is resolved (before the result lands). Resolved through the
            // registry display-name contract so raw backend ids never reach the
            // Logs UI. Metadata only: never let it break the transcription itself.
            runCatching {
                val backend = backendManager.getActiveBackend() ?: return@runCatching
                val descriptor = backendRegistry.byBackendId(backend.id)
                val name = when {
                    descriptor == null -> backend.displayName
                    descriptor.displayNameResId != null -> context.getString(descriptor.displayNameResId)
                    else -> descriptor.deriveDisplayName(context, modelPathForBackend(backend.id))
                }
                logDao.setModelName(taskId, name)
            }

            val result = when (requestType) {
                "audio" -> {
                    com.antivocale.app.util.DiagTrace.mark("orch-audio-begin", "task=$taskId")
                    processAudioRequest(
                        taskId = taskId,
                        filePath = filePath,
                        prompt = prompt,
                        queuePosition = queuePosition,
                        queueTotal = queueTotal,
                        context = context,
                        cacheDir = cacheDir,
                        listener = listener,
                        coroutineScope = coroutineScope
                    )
                }
                else -> processTextRequest(prompt)
            }
            com.antivocale.app.util.DiagTrace.mark(
                "orch-request-result",
                if (result.isSuccess) "task=$taskId SUCCESS chars=${result.getOrNull()?.text?.length}" else "task=$taskId FAILURE: ${result.exceptionOrNull()?.message}"
            )

            val duration = System.currentTimeMillis() - startTime

            result.fold(
                onSuccess = { transcriptionResult ->
                    logSuccess(
                        taskId,
                        transcriptionResult.text,
                        duration,
                        transcriptionResult.isPartial,
                        transcriptionResult.failedChunkCount
                    )
                    listener.onSuccess(taskId, transcriptionResult.text, isShareRequest, sourcePackage, duration,
                        confidence = transcriptionResult.confidence,
                        detectedLanguage = transcriptionResult.detectedLanguage,
                        isPartial = transcriptionResult.isPartial,
                        failedChunkCount = transcriptionResult.failedChunkCount
                    )
                },
                onFailure = { error ->
                    val logMsg = error.message ?: "Unknown error"
                    val userMsg = userFacingErrorMessage(context, error)
                    logError(taskId, logMsg, duration)
                    val isNoModel = isNoModelConfiguredError(error)
                    listener.onError(taskId, "INFERENCE_ERROR", userMsg, isShareRequest, isNoModel, duration)
                }
            )

            return result.map { it.text }

        } catch (e: CancellationException) {
            val duration = System.currentTimeMillis() - startTime
            cancelIfPending(taskId, "Transcription cancelled", duration)
            throw e
        } catch (e: OutOfMemoryError) {
            // TASK-396: OOM is an Error, not an Exception; without this catch it
            // escapes processRequest unhandled and the user sees a crash instead
            // of the memory advice. Keep this handler lean (the heap is exhausted):
            // reuse the existing logError/listener paths, map to the dedicated
            // string, and bail.
            Log.e(TAG, "Out of memory during transcription", e)
            val duration = System.currentTimeMillis() - startTime
            logError(taskId, "OutOfMemoryError", duration)
            listener.onError(taskId, "OUT_OF_MEMORY", "OutOfMemoryError", isShareRequest, false, duration)
            return Result.failure(TranscriptionException.InsufficientMemory(
                context.getString(R.string.error_oom_transcription)))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            val errorMsg = e.message ?: "Unknown error"
            logError(taskId, errorMsg, duration)
            listener.onError(taskId, "PROCESSING_ERROR", errorMsg, isShareRequest, false, duration)
            return Result.failure(e)
        } finally {
            if (backendOverride != null) {
                try {
                    backendManager.unloadActiveBackend()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---- Subtitle Extraction ----

    /**
     * Handles `requestType == "subtitles"`: extract embedded subtitle text without loading
     * any ASR model, and fall back to the normal ASR path when extraction yields nothing.
     *
     * @return `Result.success(text)` when subtitle text was produced and reported to
     *         [listener]; `Result.failure(...)` when the fallback ASR path itself failed
     *         (already reported to [listener]); `null` to signal the caller to run the
     *         normal ASR path (extraction returned null/blank). The `null` sentinel keeps
     *         the fallback's listener.onSuccess/onError calls in ONE place (the caller's
     *         result.fold) instead of duplicating them here.
     */
    private suspend fun processSubtitleRequest(
        taskId: String,
        filePath: String?,
        trackIndex: Int,
        source: String?,
        sourcePackage: String?,
        isShareRequest: Boolean,
        startTime: Long,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        queuePosition: Int,
        queueTotal: Int,
        prompt: String = "",
        backendOverride: String?
    ): Result<String>? {
        if (filePath.isNullOrEmpty() || trackIndex < 0) {
            Log.w(TAG, "subtitle request missing filePath or trackIndex (filePath=$filePath, trackIndex=$trackIndex) — falling back to ASR")
            listener.onStatusUpdate(context.getString(R.string.subtitle_fallback_status))
            return null
        }

        val text = try {
            SubtitleExtractor.extractToText(filePath, trackIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle extraction threw — falling back to ASR", e)
            null
        }

        if (text.isNullOrBlank()) {
            Log.w(TAG, "subtitle extraction null/blank for trackIndex=$trackIndex — falling back to ASR")
            listener.onStatusUpdate(context.getString(R.string.subtitle_fallback_status))
            return null
        }

        // Extraction succeeded: report exactly as the audio success path does, then return.
        val duration = System.currentTimeMillis() - startTime
        logSuccess(taskId, text, duration, isPartial = false, failedChunkCount = 0)
        listener.onSuccess(
            taskId,
            text,
            isShareRequest,
            sourcePackage,
            duration,
            confidence = null,
            detectedLanguage = null,
            isPartial = false,
            failedChunkCount = 0
        )
        return Result.success(text)
    }

    // ---- Backend Loading ----

    private suspend fun ensureBackendLoaded(
        context: Context,
        backendOverride: String? = null
    ): Result<Unit> {
        val hasBackend = backendManager.hasActiveBackend()
        val backendReady = backendManager.getActiveBackend()?.isReady() ?: false
        val preferredBackendId = backendOverride ?: preferencesManager.transcriptionBackend.first()
        val activeBackendId = backendManager.getActiveBackend()?.id
        val backendMismatch = hasBackend && activeBackendId != preferredBackendId

        if (!hasBackend || !backendReady || backendMismatch) {
            Log.i(TAG, "Backend needs (re)load (hasBackend=$hasBackend, ready=$backendReady, active=$activeBackendId, preferred=$preferredBackendId)")
            com.antivocale.app.util.DiagTrace.mark(
                "orch-backend-reload-needed",
                "has=$hasBackend ready=$backendReady active=$activeBackendId preferred=$preferredBackendId"
            )

            if (hasBackend) {
                Log.i(TAG, "Unloading previous backend: $activeBackendId")
                com.antivocale.app.util.DiagTrace.mark("orch-unload-prev-begin", "backend=$activeBackendId")
                backendManager.unloadActiveBackend()
                com.antivocale.app.util.DiagTrace.mark("orch-unload-prev-done")
            }

            // Sherpa-onnx consolidation: the load dispatch keys on the registry
            // descriptor (the catalog entry id); every built-in model goes through
            // the one generic [loadCatalogBackend]. The disabled GGUF backend is
            // unregistered, so its literal id is matched before the lookup; unknown
            // ids yield a null descriptor and fall through to the LLM loader, exactly
            // as the former ModelType-keyed when did.
            // External ids are intercepted BEFORE the registry lookup for a behavioral
            // reason, not a registry gap: a prefix-matched id whose record is gone must
            // fail fast with ExternalModelUnavailable instead of falling through to the
            // LLM loader (pinned by the unknown-external-id override test).
            val loadResult = if (preferredBackendId.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX)) {
                loadExternalBackend(context, preferredBackendId)
            } else when (preferredBackendId) {
                GGUF_BACKEND_ID -> loadGgufBackend(context)
                // The LLM backend ("llm") stores its model in the generic preference.
                LlmTranscriptionBackend.BACKEND_ID -> loadLlmBackend(context)
                else -> backendRegistry.byBackendId(preferredBackendId)?.let { descriptor ->
                    loadCatalogBackend(context, descriptor)
                } ?: loadLlmBackend(context)
            }

            loadResult.fold(
                onSuccess = {
                    Log.i(TAG, "Backend auto-loaded successfully: $preferredBackendId")
                    com.antivocale.app.util.DiagTrace.mark("orch-backend-loaded", "backend=$preferredBackendId")
                    val timeout = preferencesManager.keepAliveTimeout.first()
                    backendManager.setKeepAliveTimeout(timeout)
                },
                onFailure = { return Result.failure(it) }
            )
        }

        return Result.success(Unit)
    }

    private suspend fun loadLlmBackend(context: Context): Result<Unit> {
        val modelPath = preferencesManager.modelPath.first()
        if (modelPath.isNullOrBlank()) {
            return Result.failure(TranscriptionException.NotInitialized())
        }
        return backendManager.setActiveBackend(
            backendId = LlmTranscriptionBackend.BACKEND_ID,
            context = context,
            config = BackendConfig.LiteRTConfig(modelPath = modelPath)
        )
    }

    private suspend fun loadCatalogBackend(context: Context, descriptor: BackendDescriptor): Result<Unit> {
        val entry = BundledCatalog.byId(descriptor.backendId)
            ?: return Result.failure(TranscriptionException.NotInitialized())
        // The saved path is the user's explicit variant choice (useModel(variant)): honor
        // it when it still exists. Only fall back to auto-resolution when the saved path
        // is blank or its directory is gone (deleted, cleaner).
        val savedPath = descriptor.modelPathFlow(preferencesManager).first()
        val resolvedPath = when {
            !savedPath.isNullOrBlank() && File(savedPath).isDirectory -> savedPath
            else -> SherpaModelManager.of(entry.id).resolveActiveModelPath(context, fallbackPath = savedPath)
        } ?: return Result.failure(TranscriptionException.NotInitialized())
        // Persist the resolved path so the rest of the app (UI, benchmark) sees a valid path.
        if (resolvedPath != savedPath) {
            descriptor.saveModelPath(preferencesManager, resolvedPath)
        }
        // Language wiring is catalog data: languageOption (online Nemotron) passes "auto"
        // or a code per stream; passLanguage (offline Whisper) maps "auto" to "" so the
        // model auto-detects and passes a concrete code through; everything else gets "".
        val languagePref = preferencesManager.transcriptionLanguage.first()
        val language = when {
            entry.flags.languageOption ->
                if (languagePref.isBlank() || languagePref == "system") "auto" else languagePref
            entry.flags.passLanguage ->
                if (languagePref == "auto") "" else languagePref
            else -> ""
        }
        val label = when (val d = entry.display) {
            is CatalogDisplay.Resource -> context.getString(CatalogStringKeys.resolve(d.key))
            is CatalogDisplay.Literal -> d.text
        }
        return configureSherpaBackend(
            backendId = descriptor.backendId,
            modelPath = resolvedPath,
            label = label,
            language = language,
            context = context,
            modelType = entry.modelType,
        )
    }

    private fun availableMemoryBytes(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(ActivityManager::class.java)
        am?.getMemoryInfo(info)
        return info.availMem
    }

    private fun formatMb(bytes: Long): String = "${bytes / MB}MB"

    /**
     * Shared pre-flight + preference resolution + backend activation body.
     *
     * Validates the model directory, runs the OOM memory pre-flight gated by
     * [forceModelLoad], resolves thread count and inference provider, then calls
     * [backendManager.setActiveBackend] with the [configBlock] lambda.
     *
     * Shared by [configureSherpaBackend] (static sherpa-onnx backends) and
     * [loadExternalBackend] (imported external models).
     */
    private suspend fun configureBackend(
        backendId: String,
        label: String,
        modelDir: File,
        context: Context,
        configBlock: (threadCount: Int, provider: String) -> BackendConfig,
    ): Result<Unit> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("$label model directory not found: ${modelDir.absolutePath}"))
        }
        // Pre-flight memory check: refuse to load if free memory is below the model size + headroom.
        // Gated by the forceModelLoad preference so a determined user can bypass it. availMem is a
        // coarse predictor (lmkd uses PSI + oom_score_adj, not a literal MemAvailable comparison);
        // the headroom absorbs inference overhead and reclaimable-cache noise.
        if (!preferencesManager.forceModelLoad.first()) {
            com.antivocale.app.util.DiagTrace.mark("orch-mem-preflight-begin", "backend=$backendId")
            val availBytes = availableMemoryBytes(context)
            // Fail open if we could not read available memory (e.g. no ActivityManager service in
            // a test/local context): blocking on an unknown value would regress those contexts and
            // offer no real protection. Only compute the model size and compare when we have a
            // concrete measurement. This also avoids touching the filesystem (walkTopDown) when the
            // measurement is unavailable.
            if (availBytes > 0) {
                val modelSizeBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val requiredBytes = modelSizeBytes + MEMORY_HEADROOM_BYTES
                com.antivocale.app.util.DiagTrace.mark(
                    "orch-mem-preflight-measured",
                    "avail=${availBytes / MB}MB model=${modelSizeBytes / MB}MB required=${requiredBytes / MB}MB"
                )
                if (availBytes < requiredBytes) {
                    Log.w(TAG, "Blocking $label load: avail=${availBytes / MB}MB < required=${requiredBytes / MB}MB (model=${modelSizeBytes / MB}MB + headroom=${MEMORY_HEADROOM_BYTES / MB}MB)")
                    return Result.failure(TranscriptionException.InsufficientMemory(
                        context.getString(R.string.model_load_low_memory, formatMb(availBytes), formatMb(requiredBytes))
                    ))
                }
            }
        }
        Log.i(TAG, "Auto-loading $label model from: ${modelDir.absolutePath}")
        val providerPref = preferencesManager.inferenceProvider.first()
        val resolvedProvider = InferenceProvider.resolve(providerPref)
        val threadCount = preferencesManager.threadCount.first()
        Log.i(TAG, "Inference provider: pref=$providerPref resolved=$resolvedProvider")
        com.antivocale.app.util.DiagTrace.mark(
            "orch-setActiveBackend-begin",
            "backend=$backendId provider=$resolvedProvider threads=$threadCount"
        )
        val activation = backendManager.setActiveBackend(
            backendId = backendId,
            context = context,
            config = configBlock(threadCount, resolvedProvider),
        )
        com.antivocale.app.util.DiagTrace.mark(
            "orch-setActiveBackend-done",
            if (activation.isSuccess) "ok" else "FAILED: ${activation.exceptionOrNull()?.message}"
        )
        return activation
    }

    /**
     * Sherpa-onnx backend loader: validates the model dir, delegates to the shared
     * [configureBackend] for memory pre-flight and preference resolution, and builds
     * a [BackendConfig.SherpaOnnxConfig].
     */
    private suspend fun configureSherpaBackend(
        backendId: String,
        modelPath: String,
        label: String,
        language: String = "",
        context: Context,
        modelType: String = "nemo_transducer"
    ): Result<Unit> {
        return configureBackend(
            backendId = backendId,
            label = label,
            modelDir = File(modelPath),
            context = context,
        ) { threadCount, provider ->
            BackendConfig.SherpaOnnxConfig(
                modelDir = modelPath,
                modelType = modelType,
                numThreads = threadCount,
                language = language,
                provider = provider,
            )
        }
    }

    /**
     * Loads an external (user-imported) model by resolving the record from the store.
     * The [backendId] must carry [ExternalModelRecord.BACKEND_ID_PREFIX] with the record UUID after it.
     */
    private suspend fun loadExternalBackend(context: Context, backendId: String): Result<Unit> {
        val record = externalModelStore.byId(backendId.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX))
            ?: run {
                Log.w(TAG, "no external model record for $backendId")
                return Result.failure(TranscriptionException.ExternalModelUnavailable(backendId))
            }
        return configureBackend(
            backendId = record.backendId,
            label = record.displayName,
            modelDir = File(record.dir),
            context = context,
        ) { threadCount, provider ->
            BackendConfig.ExternalConfig(
                record = record,
                numThreads = threadCount,
                provider = provider,
            )
        }
    }

    // GGUF: disabled — move files from gguf-disabled/ to re-enable the body below
    private suspend fun loadGgufBackend(context: Context): Result<Unit> {
        return Result.failure(IllegalStateException("GGUF backend not available"))
        // val modelPath = preferencesManager.ggufModelPath.first()
        // if (modelPath.isNullOrBlank()) {
        //     return Result.failure(IllegalStateException("No GGUF model configured. Download or select a model in Settings."))
        // }
        // Log.i(TAG, "Auto-loading GGUF model from: $modelPath")
        // return backendManager.setActiveBackend(
        //     backendId = "gemma4_gguf",
        //     context = context,
        //     config = BackendConfig.GgufConfig(
        //         modelPath = modelPath,
        //         threadCount = preferencesManager.threadCount.first()
        //     )
        // )
    }

    // ---- Text Processing ----

    private suspend fun processTextRequest(prompt: String): Result<TranscriptionResult> {
        if (prompt.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty prompt provided"))
        }
        val backend = backendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))
        if (!backend.supportsText) {
            return Result.failure(IllegalStateException(
                "Current backend (${backend.displayName}) does not support text generation. Switch to LLM backend in Settings."
            ))
        }
        return backend.generateText(prompt).map { text -> TranscriptionResult(text = text) }
    }

    // ---- Audio Processing ----

    private suspend fun resolvePrompt(prompt: String): String {
        val savedDefaultPrompt = preferencesManager.defaultPrompt.first()
        return prompt.ifEmpty {
            savedDefaultPrompt.ifEmpty { ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT }
        }
    }

    private suspend fun processAudioRequest(
        taskId: String,
        filePath: String?,
        prompt: String = "",
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope
    ): Result<TranscriptionResult> {
        if (filePath.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("No file path provided"))
        }

        val backend = backendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))

        if (!backend.isAudioSupported()) {
            return Result.failure(IllegalStateException(
                "${backend.displayName} does not support audio transcription"
            ))
        }

        // Read settings
        val vadEnabled = preferencesManager.vadEnabled.first() ||
            // TASK-370: the LLM audio encoder is far more boundary-sensitive than
            // the ASR decoders (measured: mid-word 30s cuts garble Gemma chunks);
            // VAD-aligned segmentation is forced for the llm backend regardless
            // of the user toggle.
            backend.id == LlmTranscriptionBackend.BACKEND_ID
        val threadCount = preferencesManager.threadCount.first()
        val providerPref = preferencesManager.inferenceProvider.first()
        val resolvedProvider = InferenceProvider.resolve(providerPref)
        val progressiveEnabled = preferencesManager.progressiveTranscription.first()

        // Resolve prompt: request → settings → fallback. TASK-370: multi-chunk
        // routing lives in ChunkPromptPolicy (plain instruction per chunk,
        // custom prompt once at the end).
        val resolvedPrompt = resolvePrompt(prompt)
        val promptPlan = ChunkPromptPolicy.plan(backend.id, resolvedPrompt)

        // Use streaming pipeline for multi-chunk non-VAD scenarios
        val maxChunkDuration = backend.maxChunkDurationSeconds
        val usePipeline = !vadEnabled && maxChunkDuration != null

        val totalStartMs = System.currentTimeMillis()
        com.antivocale.app.util.DiagTrace.mark(
            "orch-route",
            "task=$taskId backend=${backend.id} vad=$vadEnabled pipeline=$usePipeline maxChunk=$maxChunkDuration progressive=$progressiveEnabled"
        )

        if (usePipeline) {
            com.antivocale.app.util.DiagTrace.mark("orch-pipeline-begin", "task=$taskId")
            return applyFinalGenerativePass(
                backend, promptPlan.finalPass,
                processPipelinedAudio(
                    taskId = taskId,
                    filePath = filePath,
                    backend = backend,
                    maxChunkDurationSeconds = maxChunkDuration,
                    context = context,
                    coroutineScope = coroutineScope,
                    listener = listener,
                    prompt = promptPlan.perChunk,
                    progressiveEnabled = progressiveEnabled
                ))
        }

        val preprocessStartMs = System.currentTimeMillis()
        com.antivocale.app.util.DiagTrace.mark("orch-preprocess-begin", "task=$taskId path=$filePath vad=$vadEnabled")
        val preprocessingResult = try {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = cacheDir,
                maxChunkDurationSeconds = backend.maxChunkDurationSeconds,
                context = context,
                enableVad = vadEnabled,
                vadNumThreads = threadCount,
                vadProvider = resolvedProvider
            )
        } catch (e: PreprocessingError) {
            com.antivocale.app.util.DiagTrace.mark("orch-preprocess-ERROR", "task=$taskId ${e.message}")
            return Result.failure(e)
        } catch (e: Exception) {
            com.antivocale.app.util.DiagTrace.mark("orch-preprocess-ERROR", "task=$taskId ${e.message}")
            return Result.failure(IllegalStateException("Audio preprocessing failed: ${e.message}"))
        }
        com.antivocale.app.util.DiagTrace.mark(
            "orch-preprocess-done",
            "task=$taskId chunks=${preprocessingResult.chunkCount} dur=${preprocessingResult.totalDurationSeconds}s sr=${preprocessingResult.sampleRate} vadSeg=${preprocessingResult.isVadSegmented}"
        )

        val chunkCount = preprocessingResult.chunkCount
        val audioDurationSeconds = preprocessingResult.totalDurationSeconds.toInt()
        val preprocessMs = System.currentTimeMillis() - preprocessStartMs
        Log.i(TAG, "PERF: preprocessing ${preprocessMs}ms for ${audioDurationSeconds}s audio, $chunkCount chunks, backend=${backend.id}, pipeline=false")

        updateAudioDuration(taskId, preprocessingResult.totalDurationSeconds)

        val transcriptionStartTime = System.currentTimeMillis()
        val chunkProcessingStartTime = System.currentTimeMillis()

        // Fast path: single chunk. Uses the streaming variant so streaming backends
        // (e.g. Nemotron) can emit progressive partials; non-streaming backends ignore
        // the callback via the default implementation in TranscriptionBackend.
        if (chunkCount == 1) {
            val t0 = System.currentTimeMillis()
            com.antivocale.app.util.DiagTrace.mark(
                "orch-infer-begin",
                "task=$taskId chunk=1/1 samples=${preprocessingResult.chunks.first().size} backend=${backend.id}"
            )
            val result = backend.transcribeAudioStreaming(
                prompt = resolvedPrompt,
                samples = preprocessingResult.chunks.first(),
                sampleRate = preprocessingResult.sampleRate
            ) { partial ->
                updateInterimResult(taskId, partial)
                listener.onInterimResult(
                    contentText = partial,
                    bigText = partial,
                    subText = "",
                    chunkIndex = 0,
                    chunkText = partial,
                    totalChunks = 1
                )
            }
            val inferMs = System.currentTimeMillis() - t0
            com.antivocale.app.util.DiagTrace.mark(
                if (result.isSuccess) "orch-infer-done" else "orch-infer-ERROR",
                "task=$taskId chunk=1/1 ${inferMs}ms ${if (result.isSuccess) "chars=${result.getOrNull()?.text?.length}" else "err=${result.exceptionOrNull()?.message}"}"
            )
            Log.i(TAG, "Inference timing: ${inferMs}ms for ${audioDurationSeconds}s audio (backend=${backend.id}, provider=$resolvedProvider, threads=${threadCount}, chunks=$chunkCount)")
            return when {
                result.isSuccess -> {
                    val tr = result.getOrNull()!!
                    if (tr.text.isNotBlank()) {
                        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)
                        Result.success(tr.copy(text = tr.text.trim()))
                    } else {
                        Result.failure(TranscriptionException.NoTranscriptionProduced())
                    }
                }
                else -> Result.failure(result.exceptionOrNull()!!)
            }
        }

        // Progressive path: VAD-segmented audio + progressive toggle enabled
        if (preprocessingResult.isVadSegmented && progressiveEnabled) {
            return applyFinalGenerativePass(
                backend, promptPlan.finalPass,
                processProgressiveSegments(
                    taskId = taskId,
                    chunks = preprocessingResult.chunks,
                    sampleRate = preprocessingResult.sampleRate,
                    prompt = promptPlan.perChunk,
                    backend = backend,
                    audioDurationSeconds = audioDurationSeconds,
                    chunkProcessingStartTime = chunkProcessingStartTime,
                    listener = listener,
                    transcriptionStartTime = transcriptionStartTime
                ))
        }

        // Multi-chunk path: parallel processing with progress tracking
        return applyFinalGenerativePass(
            backend, promptPlan.finalPass,
            processParallelChunks(
                taskId = taskId,
                chunks = preprocessingResult.chunks,
                sampleRate = preprocessingResult.sampleRate,
                prompt = promptPlan.perChunk,
                backend = backend,
                audioDurationSeconds = audioDurationSeconds,
                chunkProcessingStartTime = chunkProcessingStartTime,
                queuePosition = queuePosition,
                queueTotal = queueTotal,
                listener = listener,
                coroutineScope = coroutineScope,
                transcriptionStartTime = transcriptionStartTime,
                progressiveEnabled = progressiveEnabled
            ))
    }

    /**
     * TASK-370: the single, final application of the user's generative prompt
     * over the concatenated multi-chunk transcript. Fail-open: if the pass
     * fails or returns blank, the raw transcript is delivered unchanged (the
     * transcription itself succeeded; post-processing must not lose it).
     */
    private suspend fun applyFinalGenerativePass(
        backend: TranscriptionBackend,
        generativePrompt: String?,
        result: Result<TranscriptionResult>
    ): Result<TranscriptionResult> {
        if (generativePrompt == null || result.isFailure) return result
        val transcript = result.getOrNull()?.text?.takeIf { it.isNotBlank() } ?: return result
        return backend
            .generateText(ChunkPromptPolicy.finalPrompt(generativePrompt, transcript))
            .fold(
                onSuccess = { processed ->
                    if (processed.isNotBlank()) result.map { it.copy(text = processed.trim()) }
                    else result
                },
                onFailure = { error ->
                    Log.w(TAG, "Final generative pass failed; delivering raw transcript", error)
                    result
                })
    }

    private suspend fun processProgressiveSegments(
        taskId: String,
        chunks: List<FloatArray>,
        sampleRate: Int,
        prompt: String = "",
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        listener: TranscriptionListener,
        transcriptionStartTime: Long
    ): Result<TranscriptionResult> {
        val chunkCount = chunks.size
        Log.i(TAG, "VAD-segmented progressive path: $chunkCount segments")
        val accumulatedText = StringBuilder()
        var failedSegments = 0
        var minConfidence: Float? = null
        var detectedLang: String? = null

        for (i in chunks.indices) {
            val segNumber = i + 1
            if (accumulatedText.isEmpty()) {
                listener.onStatusUpdate("Transcribing segment 1…")
            }

            val tSeg = System.currentTimeMillis()
            com.antivocale.app.util.DiagTrace.mark(
                "orch-infer-begin",
                "task=$taskId seg=$segNumber/$chunkCount samples=${chunks[i].size}"
            )
            val segResult = backend.transcribeAudio(samples = chunks[i], sampleRate = sampleRate, prompt = prompt)
            com.antivocale.app.util.DiagTrace.mark(
                if (segResult.isSuccess) "orch-infer-done" else "orch-infer-ERROR",
                "task=$taskId seg=$segNumber/$chunkCount ${System.currentTimeMillis() - tSeg}ms"
            )
            segResult.fold(
                onSuccess = { tr ->
                    if (tr.text.isNotBlank()) {
                        val trimmed = tr.text.trim()
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                        accumulatedText.append(trimmed)
                        updateInterimResult(taskId, accumulatedText.toString())
                        Log.i(TAG, "Progressive preview: segment ${trimmed.length} chars, total ${accumulatedText.length} chars")
                        listener.onInterimResult(
                            contentText = trimmed,
                            bigText = trimmed,
                            subText = "Segment $segNumber/$chunkCount",
                            chunkIndex = i,
                            chunkText = trimmed,
                            totalChunks = chunkCount
                        )
                    }
                    minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                    if (detectedLang == null) detectedLang = tr.detectedLanguage
                },
                onFailure = { error ->
                    failedSegments++
                    Log.e(TAG, "Segment $segNumber/$chunkCount failed", error)
                }
            )
        }

        val totalMs = System.currentTimeMillis() - chunkProcessingStartTime
        Log.i(TAG, "PERF: progressive total ${totalMs}ms for ${audioDurationSeconds}s audio, $chunkCount segments, backend=${backend.id}")
        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        return if (accumulatedText.isEmpty()) {
            Result.failure(IllegalStateException("All $chunkCount segments failed to transcribe"))
        } else {
            if (failedSegments > 0) {
                Log.w(TAG, "Completed with $failedSegments/$chunkCount failed segments")
            }
            Result.success(TranscriptionResult(
                text = accumulatedText.toString(),
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedSegments > 0,
                failedChunkCount = failedSegments
            ))
        }
    }

    private suspend fun processParallelChunks(
        taskId: String,
        chunks: List<FloatArray>,
        sampleRate: Int,
        prompt: String = "",
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        queuePosition: Int,
        queueTotal: Int,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        transcriptionStartTime: Long,
        progressiveEnabled: Boolean = false
    ): Result<TranscriptionResult> {
        val chunkCount = chunks.size
        val completedChunks = AtomicInteger(0)
        val results = arrayOfNulls<String>(chunkCount)
        val chunkConfidences = arrayOfNulls<Float>(chunkCount)
        val chunkLanguages = arrayOfNulls<String>(chunkCount)

        Log.i(TAG, "Processing $chunkCount chunks with up to $MAX_CONCURRENT_CHUNKS concurrent transcriptions")

        val backendId = backend.id
        val modelPath = modelPathForBackend(backendId)
        val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)
        val calibrationProfile = transcriptionCalibrator.getEstimate(backendId, modelPath)
        val chunkDurationSeconds = (audioDurationSeconds.toDouble() / chunkCount).toLong()
        val estimatedChunkDurationMs = calibrationProfile?.let {
            if (it.hasEstimate) (it.msPerSecondOfAudio * chunkDurationSeconds).toLong() else null
        }
        val estimatedTotalMs = estimatedChunkDurationMs?.let { est ->
            val batches = ceilDiv(chunkCount, MAX_CONCURRENT_CHUNKS).toLong()
            batches * est
        }

        val progressTimerJob = coroutineScope.launch {
            startGlobalProgressTimer(
                totalChunks = chunkCount,
                completedChunks = completedChunks,
                estimatedTotalMs = estimatedTotalMs,
                audioDurationSeconds = audioDurationSeconds,
                calibrationProfile = calibrationProfile,
                queuePosition = queuePosition,
                queueTotal = queueTotal,
                backendId = backendId,
                modelPath = modelPath,
                modelDisplayName = modelDisplayName,
                chunkDurationSeconds = chunkDurationSeconds,
                transcriptionStartTime = transcriptionStartTime,
                listener = listener
            )
        }

        var failedChunks = 0

        coroutineScope {
            val deferredResults = chunks.mapIndexed { index, chunk ->
                async {
                    chunkSemaphore.acquire()
                    val tChunk = System.currentTimeMillis()
                    com.antivocale.app.util.DiagTrace.mark(
                        "orch-infer-begin",
                        "task=$taskId chunk=${index + 1}/$chunkCount samples=${chunk.size}"
                    )
                    try {
                        val chunkResult = backend.transcribeAudio(samples = chunk, sampleRate = sampleRate, prompt = prompt)
                        completedChunks.incrementAndGet()
                        com.antivocale.app.util.DiagTrace.mark(
                            if (chunkResult.isSuccess) "orch-infer-done" else "orch-infer-ERROR",
                            "task=$taskId chunk=${index + 1}/$chunkCount ${System.currentTimeMillis() - tChunk}ms"
                        )
                        chunkResult
                    } finally {
                        chunkSemaphore.release()
                    }
                }
            }

            val progressiveText = if (progressiveEnabled) StringBuilder() else null

            deferredResults.forEachIndexed { index, deferred ->
                val chunkResult = deferred.await()
                chunkResult.fold(
                    onSuccess = { tr ->
                        if (tr.text.isNotBlank()) {
                            val trimmed = tr.text.trim()
                            results[index] = trimmed
                            chunkConfidences[index] = tr.confidence
                            chunkLanguages[index] = tr.detectedLanguage
                            if (progressiveText != null) {
                                if (progressiveText.isNotEmpty()) progressiveText.append(' ')
                                progressiveText.append(trimmed)
                                updateInterimResult(taskId, progressiveText.toString())
                                listener.onInterimResult(
                                    contentText = trimmed,
                                    bigText = trimmed,
                                    subText = "Chunk ${index + 1}/$chunkCount",
                                    chunkIndex = index,
                                    chunkText = trimmed,
                                    totalChunks = chunkCount
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Parallel chunk ${index + 1} failed, retrying with memory cleanup", error)
                        val retried = retryChunkWithGc(backend, chunks[index], sampleRate, prompt)
                        retried.fold(
                            onSuccess = { tr ->
                                Log.i(TAG, "Parallel chunk ${index + 1} retry succeeded")
                                if (tr.text.isNotBlank()) {
                                    val trimmed = tr.text.trim()
                                    results[index] = trimmed
                                    chunkConfidences[index] = tr.confidence
                                    chunkLanguages[index] = tr.detectedLanguage
                                }
                            },
                            onFailure = { retryError ->
                                failedChunks++
                                Log.e(TAG, "Parallel chunk ${index + 1} retry also failed", retryError)
                            }
                        )
                    }
                )
            }
        }

        progressTimerJob.cancel()

        val combinedResult = results.filterNotNull().joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.filterNotNull().size}/$chunkCount chunks")

        val totalMs = System.currentTimeMillis() - chunkProcessingStartTime
        Log.i(TAG, "PERF: parallel total ${totalMs}ms for ${audioDurationSeconds}s audio, $chunkCount chunks, backend=${backend.id}")

        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        return if (combinedResult.isBlank()) {
            Result.failure(TranscriptionException.NoTranscriptionProduced())
        } else {
            if (failedChunks > 0) {
                Log.w(TAG, "Parallel completed with $failedChunks/$chunkCount failed chunks")
            }
            val minConfidence = chunkConfidences.filterNotNull().minOrNull()
            val detectedLang = chunkLanguages.firstOrNull { it != null }
            Result.success(TranscriptionResult(
                text = combinedResult,
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedChunks > 0,
                failedChunkCount = failedChunks
            ))
        }
    }

    /**
     * Pipelined processing: transcribes chunks as they're decoded, overlapping
     * MediaCodec decoding with backend inference. Time-to-first-text improves
     * from ~3-5s to ~700ms for long audio.
     */
    private suspend fun processPipelinedAudio(
        taskId: String,
        filePath: String,
        backend: TranscriptionBackend,
        maxChunkDurationSeconds: Int,
        context: Context,
        coroutineScope: CoroutineScope,
        listener: TranscriptionListener,
        prompt: String = "",
        progressiveEnabled: Boolean = false
    ): Result<TranscriptionResult> {
        val resolvedPrompt = resolvePrompt(prompt)

        val pipelineStartMs = System.currentTimeMillis()
        val chunkProcessingStartTime = System.currentTimeMillis()
        val accumulatedText = StringBuilder()
        var totalDurationSeconds = 0.0
        var expectedChunkCount = 0
        var firstChunkDecodeMs = 0L
        var firstChunkInferStartMs = 0L
        var failedChunks = 0
        var minConfidence: Float? = null
        var detectedLang: String? = null

        try {
            audioPreprocessor.prepareAudioStream(
                inputPath = filePath,
                maxChunkDurationSeconds = maxChunkDurationSeconds,
                context = context,
                enableVad = false
            ).collect { event ->
                when (event) {
                    is AudioPreprocessor.StreamEvent.Header -> {
                        totalDurationSeconds = event.header.totalDurationSeconds
                        expectedChunkCount = event.header.expectedChunkCount
                        updateAudioDuration(taskId, event.header.totalDurationSeconds)
                        Log.i(TAG, "Pipeline: expecting ${event.header.expectedChunkCount} chunks, ${event.header.totalDurationSeconds}s")
                    }
                    is AudioPreprocessor.StreamEvent.Chunk -> {
                        val chunk = event.chunk
                        val chunkReceiveMs = System.currentTimeMillis() - pipelineStartMs
                        if (chunk.chunkIndex == 0) {
                            firstChunkDecodeMs = chunkReceiveMs
                            firstChunkInferStartMs = System.currentTimeMillis()
                            Log.i(TAG, "PERF: pipeline first chunk decoded in ${firstChunkDecodeMs}ms")
                        }
                        Log.d(TAG, "Pipeline: transcribing chunk ${chunk.chunkIndex} (${chunk.samples.size} samples)")

                        val tChunk = System.currentTimeMillis()
                        com.antivocale.app.util.DiagTrace.mark(
                            "orch-infer-begin",
                            "task=$taskId pipe-chunk=${chunk.chunkIndex + 1}/$expectedChunkCount samples=${chunk.samples.size}"
                        )
                        val chunkResult = backend.transcribeAudio(
                            samples = chunk.samples,
                            sampleRate = chunk.sampleRate,
                            prompt = resolvedPrompt
                        )
                        com.antivocale.app.util.DiagTrace.mark(
                            if (chunkResult.isSuccess) "orch-infer-done" else "orch-infer-ERROR",
                            "task=$taskId pipe-chunk=${chunk.chunkIndex + 1}/$expectedChunkCount ${System.currentTimeMillis() - tChunk}ms"
                        )
                        chunkResult.fold(
                            onSuccess = { tr ->
                                if (tr.text.isNotBlank()) {
                                    val trimmed = tr.text.trim()
                                    if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                                    accumulatedText.append(trimmed)
                                    if (progressiveEnabled) {
                                        updateInterimResult(taskId, accumulatedText.toString())
                                        listener.onInterimResult(
                                            contentText = trimmed,
                                            bigText = trimmed,
                                            subText = "Chunk ${chunk.chunkIndex + 1}/$expectedChunkCount",
                                            chunkIndex = chunk.chunkIndex,
                                            chunkText = trimmed,
                                            totalChunks = expectedChunkCount
                                        )
                                    }
                                }
                                minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                                if (detectedLang == null) detectedLang = tr.detectedLanguage
                            },
                            onFailure = { error ->
                                Log.w(TAG, "Pipeline chunk ${chunk.chunkIndex} failed, retrying with memory cleanup", error)
                                val retried = retryChunkWithGc(backend, chunk.samples, chunk.sampleRate, resolvedPrompt)
                                retried.fold(
                                    onSuccess = { tr ->
                                        Log.i(TAG, "Pipeline chunk ${chunk.chunkIndex} retry succeeded")
                                        if (tr.text.isNotBlank()) {
                                            val trimmed = tr.text.trim()
                                            if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                                            accumulatedText.append(trimmed)
                                            if (progressiveEnabled) {
                                                updateInterimResult(taskId, accumulatedText.toString())
                                                listener.onInterimResult(
                                                    contentText = trimmed,
                                                    bigText = trimmed,
                                                    subText = "Chunk ${chunk.chunkIndex + 1}/$expectedChunkCount (retry)",
                                                    chunkIndex = chunk.chunkIndex,
                                                    chunkText = trimmed,
                                                    totalChunks = expectedChunkCount
                                                )
                                            }
                                        }
                                        minConfidence = aggregateConfidence(minConfidence, tr.confidence)
                                        if (detectedLang == null) detectedLang = tr.detectedLanguage
                                    },
                                    onFailure = { retryError ->
                                        failedChunks++
                                        Log.e(TAG, "Pipeline chunk ${chunk.chunkIndex} retry also failed", retryError)
                                    }
                                )
                            }
                        )

                        if (chunk.chunkIndex == 0 && accumulatedText.isNotEmpty()) {
                            val ttft = System.currentTimeMillis() - firstChunkInferStartMs
                            Log.i(TAG, "PERF: pipeline time-to-first-text = ${System.currentTimeMillis() - pipelineStartMs}ms (decode=${firstChunkDecodeMs}ms + infer=${ttft}ms)")
                            if (!progressiveEnabled) {
                                listener.onStatusUpdate("Transcribing…")
                            }
                        }
                    }
                }
            }
        } catch (e: PreprocessingError) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Pipeline failed: ${e.message}"))
        }

        val combinedResult = accumulatedText.toString()
        recordCalibration(backend, totalDurationSeconds.toInt(), chunkProcessingStartTime)

        val totalMs = System.currentTimeMillis() - pipelineStartMs
        Log.i(TAG, "PERF: pipeline total ${totalMs}ms for ${totalDurationSeconds}s audio, ${expectedChunkCount} chunks, backend=${backend.id}, ttft_decode=${firstChunkDecodeMs}ms")

        return if (combinedResult.isBlank()) {
            Result.failure(TranscriptionException.NoTranscriptionProduced())
        } else {
            if (failedChunks > 0) {
                Log.w(TAG, "Pipeline completed with $failedChunks/$expectedChunkCount failed chunks")
            }
            Result.success(TranscriptionResult(
                text = combinedResult,
                confidence = minConfidence,
                detectedLanguage = detectedLang,
                isPartial = failedChunks > 0,
                failedChunkCount = failedChunks
            ))
        }
    }

    // ---- Progress Timer ----

    private fun CoroutineScope.startGlobalProgressTimer(
        totalChunks: Int,
        completedChunks: AtomicInteger,
        estimatedTotalMs: Long?,
        audioDurationSeconds: Int,
        calibrationProfile: TranscriptionCalibrator.CalibrationProfile?,
        queuePosition: Int,
        queueTotal: Int,
        backendId: String,
        modelPath: String,
        modelDisplayName: String,
        chunkDurationSeconds: Long,
        transcriptionStartTime: Long,
        listener: TranscriptionListener
    ): Job {
        val startTime = System.currentTimeMillis()
        var lastProgressPercent = -1


        var lastBatchCompletedCount = 0
        var lastBatchElapsedMs: Long? = null
        var measuredAvgBatchMs: Long? = null
        var firstBatchRecorded = false

        return launch {
            while (isActive) {
                delay(200)

                val now = System.currentTimeMillis()
                val elapsedMs = now - startTime
                val completed = completedChunks.get()

                // Feedback loop: detect batch completions and measure actual throughput
                val completedBatches = completed / MAX_CONCURRENT_CHUNKS
                if (completedBatches > lastBatchCompletedCount) {
                    val prevBatchElapsed = lastBatchElapsedMs
                    measuredAvgBatchMs = if (prevBatchElapsed != null) {
                        elapsedMs - prevBatchElapsed
                    } else {
                        elapsedMs
                    }
                    lastBatchElapsedMs = elapsedMs
                    lastBatchCompletedCount = completedBatches

                    if (!firstBatchRecorded && backendId.isNotEmpty()) {
                        firstBatchRecorded = true
                        val batchAudioSeconds = chunkDurationSeconds * MAX_CONCURRENT_CHUNKS
                        val batchMs = measuredAvgBatchMs!!
                        launch {
                            try {
                                transcriptionCalibrator.record(
                                    backendId = backendId,
                                    modelPath = modelPath,
                                    displayName = modelDisplayName,
                                    audioDurationSeconds = batchAudioSeconds,
                                    processingTimeMs = batchMs
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed mid-transcription calibration", e)
                            }
                        }
                    }
                }

                val hardPercent = completed * 100 / totalChunks

                val adaptiveEtaMs: Long? = if (measuredAvgBatchMs != null) {
                    val remainingBatches = ceilDiv(totalChunks - completed, MAX_CONCURRENT_CHUNKS).toLong()
                    remainingBatches * measuredAvgBatchMs
                } else if (estimatedTotalMs != null) {
                    maxOf(0L, estimatedTotalMs - elapsedMs)
                } else null

                val timePercent = if (adaptiveEtaMs != null && adaptiveEtaMs > 0) {
                    (elapsedMs.toFloat() / (elapsedMs + adaptiveEtaMs) * 100f).toInt().coerceIn(0, 95)
                } else {
                    val crawlTarget = audioDurationSeconds * 1000f * 2f
                    (elapsedMs / crawlTarget * 80f).toInt().coerceIn(0, 80)
                }

                val displayProgress = maxOf(1, hardPercent, timePercent).coerceIn(0, 99)
                if (displayProgress == lastProgressPercent) continue
                lastProgressPercent = displayProgress

                val etaText = adaptiveEtaMs?.let { eta ->
                    val confidence = calibrationProfile?.confidence
                        ?: TranscriptionCalibrator.CalibrationProfile.Confidence.LOW
                    formatEta(eta / 1000, confidence)
                } ?: if (calibrationProfile != null && !calibrationProfile.hasEstimate) {
                    "Calibrating…"
                } else {
                    ""
                }

                val contentText = if (completed == 0 && totalChunks > 1) {
                    queueAwareAudioLabel(queuePosition, queueTotal)
                } else {
                    queueAwareChunkLabel(completed, totalChunks, queuePosition, queueTotal)
                }

                listener.onProgress(
                    contentText = contentText,
                    progressPercent = displayProgress,
                    etaText = etaText,
                    durationSeconds = audioDurationSeconds,
                    startTimeMillis = transcriptionStartTime,
                    queuedCount = 0 // queue count managed by service
                )
            }
        }
    }

    // ---- Calibration ----

    private suspend fun recordCalibration(
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        startTimeMs: Long
    ) {
        val totalProcessingTimeMs = System.currentTimeMillis() - startTimeMs
        try {
            val backendId = backend.id
            val modelPath = modelPathForBackend(backendId)
            val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)
            transcriptionCalibrator.record(
                backendId = backendId,
                modelPath = modelPath,
                displayName = modelDisplayName,
                audioDurationSeconds = audioDurationSeconds.toLong(),
                processingTimeMs = totalProcessingTimeMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record calibration", e)
        }
    }

    // ---- DB Logging ----

    /**
     * Creates the log entry at enqueue time (GH #51): the request is visible in
     * the Logs tab as QUEUED from the moment it enters the queue, before work
     * starts. Called by InferenceService when a request is accepted. This is the
     * single insert point for a request's row.
     */
    suspend fun logQueued(
        taskId: String,
        requestType: String,
        prompt: String = "",
        filePath: String? = null,
        sourcePackageName: String? = null
    ) {
        logDao.insert(
            LogEntry(
                taskId = taskId,
                type = if (requestType == "audio" || requestType == "subtitles") LogEntry.Type.AUDIO else LogEntry.Type.TEXT,
                status = LogEntry.Status.QUEUED,
                prompt = prompt,
                filePath = filePath,
                sourcePackageName = sourcePackageName
            ).toEntity()
        )
    }

    /**
     * Promotes the QUEUED entry (created by [logQueued]) to PROCESSING when work
     * starts. DAO-level and non-inserting by design: this racing the enqueue
     * write can never produce a duplicate row, and an entry the user deleted
     * mid-flight stays deleted (no resurrect).
     */
    suspend fun markProcessing(taskId: String) {
        logDao.promoteToProcessing(taskId)
    }

    private suspend fun logSuccess(
        taskId: String,
        result: String,
        durationMs: Long,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0
    ) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.SUCCESS, result = result, durationMs = durationMs,
            isPartial = isPartial, failedChunkCount = failedChunkCount
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
        lastInterimRoomWriteMs.remove(taskId)
    }

    private suspend fun logError(taskId: String, errorMessage: String, durationMs: Long = 0) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.ERROR, errorMessage = errorMessage, durationMs = durationMs
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
        lastInterimRoomWriteMs.remove(taskId)
    }

    private suspend fun cancelIfPending(taskId: String, errorMessage: String, durationMs: Long) {
        lastInterimRoomWriteMs.remove(taskId)
        logDao.failNonTerminal(taskId, errorMessage, durationMs)
    }

    private suspend fun updateInterimResult(taskId: String, accumulatedText: String) {
        // Throttle interim Room writes to the same 5s cadence as the partial-state save
        // (TASK-340 Fix 2b): every interim partial used to write Room, and each write
        // re-emitted the whole (bounded) log list through LogsViewModel. The final
        // result is written unconditionally by logSuccess, so skipping writes inside
        // the interval cannot lose text; notifications still fire per chunk via the
        // listener, which is NOT throttled here.
        val now = throttleClock()
        if (now - (lastInterimRoomWriteMs[taskId] ?: 0L) < PARTIAL_SAVE_INTERVAL_MS) return
        lastInterimRoomWriteMs[taskId] = now

        // TASK-390: column-scoped update (no read): a whole-row write-back could
        // resurrect a row that a concurrent close (cancel/sweep) had just terminalized.
        logDao.updateInterimResult(taskId, accumulatedText, isPartial = true)

        if (now - lastPartialSaveMs >= PARTIAL_SAVE_INTERVAL_MS) {
            lastPartialSaveMs = now
            try {
                preferencesManager.savePartialTranscriptionState(accumulatedText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save partial transcription state", e)
            }
        }
    }

    private suspend fun updateAudioDuration(taskId: String, audioDurationSeconds: Double) {
        // TASK-390: column-scoped, see updateInterimResult.
        logDao.updateAudioDuration(taskId, audioDurationSeconds)
    }

    // ---- Chunk Retry ----

    /** Retries after GC to reclaim ONNX tensor memory on low-RAM devices. */
    private suspend fun retryChunkWithGc(
        backend: TranscriptionBackend,
        samples: FloatArray,
        sampleRate: Int,
        prompt: String
    ): Result<TranscriptionResult> {
        System.gc()
        delay(100)
        return backend.transcribeAudio(samples = samples, sampleRate = sampleRate, prompt = prompt)
    }

    // ---- Utilities ----

    /**
     * Saved model path for [backendId], read via the registry descriptor's model-path
     * flow (TASK-322; the descriptor for the LLM backend already points at the generic
     * [PreferencesManager.modelPath]). The unregistered GGUF backend keeps its dedicated
     * preference and any other unknown id degrades to the generic one, matching the
     * former string-keyed when.
     */
    private suspend fun modelPathForBackend(backendId: String): String {
        val descriptor = backendRegistry.byBackendId(backendId)
        return when {
            descriptor != null -> descriptor.modelPathFlow(preferencesManager).first()
            backendId == GGUF_BACKEND_ID -> preferencesManager.ggufModelPath.first()
            else -> preferencesManager.modelPath.first()
        } ?: ""
    }

    internal fun deriveDisplayName(backendId: String, modelPath: String, fallbackName: String?): String {
        val dirName = File(modelPath).name
        return when (backendId) {
            BuiltInBackendIds.WHISPER -> {
                val variant = dirName.removePrefix("sherpa-onnx-whisper-")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                if (variant.isNotEmpty()) "Whisper $variant" else fallbackName ?: "Whisper"
            }
            BuiltInBackendIds.QWEN3_ASR -> {
                dirName.removePrefix("sherpa-onnx-qwen3-asr-")
                    .replace("-int8", "")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
            }
            else -> fallbackName ?: backendId
        }
    }

    internal fun formatEta(
        remainingSeconds: Long,
        confidence: TranscriptionCalibrator.CalibrationProfile.Confidence
    ): String {
        if (remainingSeconds <= 0) return ""
        return when (confidence) {
            TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH -> {
                when {
                    remainingSeconds < 60 -> "${remainingSeconds}s remaining"
                    remainingSeconds < 3600 -> {
                        val min = remainingSeconds / 60
                        val sec = remainingSeconds % 60
                        "~${min}m ${sec}s remaining"
                    }
                    else -> {
                        val hr = remainingSeconds / 3600
                        val min = (remainingSeconds % 3600) / 60
                        "~${hr}h ${min}m remaining"
                    }
                }
            }
            TranscriptionCalibrator.CalibrationProfile.Confidence.LOW -> {
                val min = remainingSeconds / 60
                val sec = remainingSeconds % 60
                "Est. ~${min}m ${sec}s remaining"
            }
            else -> ""
        }
    }

    internal fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    internal fun aggregateConfidence(current: Float?, next: Float?): Float? {
        if (current == null) return next
        if (next == null) return current
        return minOf(current, next)
    }

    internal fun queueAwareAudioLabel(queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing audio ($queuePosition of $queueTotal)…"
        else "Processing audio…"

    internal fun queueAwareChunkLabel(completed: Int, totalChunks: Int, queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing chunk $completed/$totalChunks ($queuePosition of $queueTotal)…"
        else "Processing chunk $completed/$totalChunks…"
}
