package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.toEntity
import com.antivocale.app.data.local.toLogEntry
import com.antivocale.app.service.TranscriptionListener
import com.antivocale.app.ui.viewmodel.LogEntry
import kotlinx.coroutines.*
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
    private val audioPreprocessor: AudioPreprocessor
) {
    companion object {
        private const val TAG = "TranscriptionOrchestrator"
        private const val MAX_CONCURRENT_CHUNKS = 2
        private const val PARTIAL_SAVE_INTERVAL_MS = 5000L
    }

    @Volatile
    private var lastPartialSaveMs: Long = 0L

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
        prompt: String,
        filePath: String?,
        source: String?,
        sourcePackage: String?,
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope
    ): Result<String> {
        val isShareRequest = source == "share"

        // Log request start
        logRequest(
            taskId = taskId,
            type = if (requestType == "audio") LogEntry.Type.AUDIO else LogEntry.Type.TEXT,
            prompt = prompt,
            filePath = filePath,
            sourcePackageName = sourcePackage
        )

        val startTime = System.currentTimeMillis()

        try {
            // Ensure the correct backend is loaded
            val loadResult = ensureBackendLoaded(context)
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()!!
                val errorMsg = "Failed to load backend: ${error.message}"
                val duration = System.currentTimeMillis() - startTime
                val isNoModel = isNoModelConfiguredError(error)
                logError(taskId, errorMsg, duration)
                listener.onError(taskId, "BACKEND_LOAD_FAILED", errorMsg, isShareRequest, isNoModel, duration)
                return Result.failure(error)
            }

            val result = when (requestType) {
                "audio" -> processAudioRequest(
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
                else -> processTextRequest(prompt)
            }

            val duration = System.currentTimeMillis() - startTime

            result.fold(
                onSuccess = { response ->
                    logSuccess(taskId, response, duration)
                    listener.onSuccess(taskId, response, isShareRequest, sourcePackage, duration)
                },
                onFailure = { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    logError(taskId, errorMsg, duration)
                    val isNoModel = isNoModelConfiguredError(error)
                    listener.onError(taskId, "INFERENCE_ERROR", errorMsg, isShareRequest, isNoModel, duration)
                }
            )

            return result

        } catch (e: CancellationException) {
            val duration = System.currentTimeMillis() - startTime
            cancelIfPending(taskId, "Transcription cancelled", duration)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            val errorMsg = e.message ?: "Unknown error"
            logError(taskId, errorMsg, duration)
            listener.onError(taskId, "PROCESSING_ERROR", errorMsg, isShareRequest, false, duration)
            return Result.failure(e)
        }
    }

    // ---- Backend Loading ----

    private suspend fun ensureBackendLoaded(context: Context): Result<Unit> {
        val hasBackend = backendManager.hasActiveBackend()
        val backendReady = backendManager.getActiveBackend()?.isReady() ?: false
        val preferredBackendId = preferencesManager.transcriptionBackend.first()
        val activeBackendId = backendManager.getActiveBackend()?.id
        val backendMismatch = hasBackend && activeBackendId != preferredBackendId

        if (!hasBackend || !backendReady || backendMismatch) {
            Log.i(TAG, "Backend needs (re)load (hasBackend=$hasBackend, ready=$backendReady, active=$activeBackendId, preferred=$preferredBackendId)")

            if (hasBackend) {
                Log.i(TAG, "Unloading previous backend: $activeBackendId")
                backendManager.unloadActiveBackend()
            }

            val loadResult = when (preferredBackendId) {
                SherpaOnnxBackend.BACKEND_ID -> loadSherpaOnnxBackend(context)
                WhisperBackend.BACKEND_ID -> loadWhisperBackend(context)
                Qwen3AsrBackend.BACKEND_ID -> loadQwen3AsrBackend(context)
                Gemma4GgufBackend.BACKEND_ID -> loadGgufBackend(context)
                else -> loadLlmBackend(context)
            }

            loadResult.fold(
                onSuccess = {
                    Log.i(TAG, "Backend auto-loaded successfully: $preferredBackendId")
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
            return Result.failure(IllegalStateException("No LLM model configured. Download or select a model in Settings."))
        }
        return backendManager.setActiveBackend(
            backendId = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
            context = context,
            config = BackendConfig.LiteRTConfig(modelPath = modelPath)
        )
    }

    private suspend fun loadSherpaOnnxModel(
        backendId: String,
        modelPathFlow: kotlinx.coroutines.flow.Flow<String?>,
        label: String,
        language: String = "",
        context: Context
    ): Result<Unit> {
        val modelPath = modelPathFlow.first()
        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No $label model configured. Download or select a model in Settings."))
        }
        val modelDir = File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("$label model directory not found: $modelPath"))
        }
        Log.i(TAG, "Auto-loading $label model from: $modelPath")
        val providerPref = preferencesManager.inferenceProvider.first()
        val resolvedProvider = InferenceProvider.resolve(providerPref)
        Log.i(TAG, "Inference provider: pref=$providerPref resolved=$resolvedProvider")
        return backendManager.setActiveBackend(
            backendId = backendId,
            context = context,
            config = BackendConfig.SherpaOnnxConfig(
                modelDir = modelPath,
                numThreads = preferencesManager.threadCount.first(),
                language = language,
                provider = resolvedProvider
            )
        )
    }

    private suspend fun loadSherpaOnnxBackend(context: Context) = loadSherpaOnnxModel(
        backendId = SherpaOnnxBackend.BACKEND_ID,
        modelPathFlow = preferencesManager.parakeetModelPath,
        label = "Parakeet",
        context = context
    )

    private suspend fun loadWhisperBackend(context: Context) = loadSherpaOnnxModel(
        backendId = WhisperBackend.BACKEND_ID,
        modelPathFlow = preferencesManager.whisperModelPath,
        label = "Whisper",
        language = preferencesManager.transcriptionLanguage.first().let { lang ->
            if (lang == "auto") "" else lang
        },
        context = context
    )

    private suspend fun loadQwen3AsrBackend(context: Context) = loadSherpaOnnxModel(
        backendId = Qwen3AsrBackend.BACKEND_ID,
        modelPathFlow = preferencesManager.qwen3AsrModelPath,
        label = "Qwen3-ASR",
        context = context
    )

    private suspend fun loadGgufBackend(context: Context): Result<Unit> {
        val modelPath = preferencesManager.ggufModelPath.first()
        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No GGUF model configured. Download or select a model in Settings."))
        }
        Log.i(TAG, "Auto-loading GGUF model from: $modelPath")
        return backendManager.setActiveBackend(
            backendId = Gemma4GgufBackend.BACKEND_ID,
            context = context,
            config = BackendConfig.GgufConfig(
                modelPath = modelPath,
                threadCount = preferencesManager.threadCount.first()
            )
        )
    }

    // ---- Text Processing ----

    private suspend fun processTextRequest(prompt: String): Result<String> {
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
        return backend.generateText(prompt)
    }

    // ---- Audio Processing ----

    private suspend fun processAudioRequest(
        taskId: String,
        filePath: String?,
        prompt: String,
        queuePosition: Int,
        queueTotal: Int,
        context: Context,
        cacheDir: File,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope
    ): Result<String> {
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

        // Preprocess audio
        val vadEnabled = preferencesManager.vadEnabled.first()
        val threadCount = preferencesManager.threadCount.first()
        val providerPref = preferencesManager.inferenceProvider.first()
        val resolvedProvider = InferenceProvider.resolve(providerPref)
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
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Audio preprocessing failed: ${e.message}"))
        }

        val chunkCount = preprocessingResult.chunkCount
        val audioDurationSeconds = preprocessingResult.totalDurationSeconds.toInt()
        Log.i(TAG, "Audio preprocessed: $chunkCount chunks, ${audioDurationSeconds}s")

        updateAudioDuration(taskId, preprocessingResult.totalDurationSeconds)

        val transcriptionStartTime = System.currentTimeMillis()
        val chunkProcessingStartTime = System.currentTimeMillis()

        // Resolve prompt: request → settings → fallback
        val savedDefaultPrompt = preferencesManager.defaultPrompt.first()
        val resolvedPrompt = prompt.ifEmpty {
            savedDefaultPrompt.ifEmpty { "Transcribe the following audio recording." }
        }

        // Fast path: single chunk
        if (chunkCount == 1) {
            val t0 = System.currentTimeMillis()
            val result = backend.transcribeAudio(
                prompt = resolvedPrompt,
                audioData = preprocessingResult.chunks.first()
            )
            val inferMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Inference timing: ${inferMs}ms for ${audioDurationSeconds}s audio (backend=${backend.id}, provider=$resolvedProvider, threads=${threadCount}, chunks=$chunkCount)")
            return when {
                result.isSuccess && result.getOrNull()?.isNotBlank() == true -> {
                    recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)
                    Result.success(result.getOrNull()!!.trim())
                }
                result.isFailure -> Result.failure(result.exceptionOrNull()!!)
                else -> Result.failure(IllegalStateException("No transcription produced"))
            }
        }

        // Progressive path: VAD-segmented audio + progressive toggle enabled
        val progressiveEnabled = preferencesManager.progressiveTranscription.first()
        if (preprocessingResult.isVadSegmented && progressiveEnabled) {
            return processProgressiveSegments(
                taskId = taskId,
                chunks = preprocessingResult.chunks,
                prompt = resolvedPrompt,
                backend = backend,
                audioDurationSeconds = audioDurationSeconds,
                chunkProcessingStartTime = chunkProcessingStartTime,
                listener = listener,
                transcriptionStartTime = transcriptionStartTime
            )
        }

        // Multi-chunk path: parallel processing with progress tracking
        return processParallelChunks(
            chunks = preprocessingResult.chunks,
            prompt = resolvedPrompt,
            backend = backend,
            audioDurationSeconds = audioDurationSeconds,
            chunkProcessingStartTime = chunkProcessingStartTime,
            queuePosition = queuePosition,
            queueTotal = queueTotal,
            listener = listener,
            coroutineScope = coroutineScope,
            transcriptionStartTime = transcriptionStartTime
        )
    }

    private suspend fun processProgressiveSegments(
        taskId: String,
        chunks: List<ByteArray>,
        prompt: String,
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        listener: TranscriptionListener,
        transcriptionStartTime: Long
    ): Result<String> {
        val chunkCount = chunks.size
        Log.i(TAG, "VAD-segmented progressive path: $chunkCount segments")
        val accumulatedText = StringBuilder()
        var failedSegments = 0

        for (i in chunks.indices) {
            val segNumber = i + 1
            if (accumulatedText.isEmpty()) {
                listener.onStatusUpdate("Transcribing segment 1…")
            }

            val segResult = backend.transcribeAudio(prompt = prompt, audioData = chunks[i])
            segResult.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        val trimmed = text.trim()
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                        accumulatedText.append(trimmed)
                        updateInterimResult(taskId, accumulatedText.toString())
                        val latestSegment = text.trim()
                        Log.i(TAG, "Progressive preview: segment ${latestSegment.length} chars, total ${accumulatedText.length} chars")
                        listener.onInterimResult(
                            contentText = latestSegment,
                            bigText = latestSegment,
                            subText = "Segment $segNumber/$chunkCount"
                        )
                    }
                },
                onFailure = { error ->
                    failedSegments++
                    Log.e(TAG, "Segment $segNumber/$chunkCount failed", error)
                }
            )
        }

        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        return if (accumulatedText.isEmpty()) {
            Result.failure(IllegalStateException("All $chunkCount segments failed to transcribe"))
        } else {
            if (failedSegments > 0) {
                Log.w(TAG, "Completed with $failedSegments/$chunkCount failed segments")
            }
            Result.success(accumulatedText.toString())
        }
    }

    private suspend fun processParallelChunks(
        chunks: List<ByteArray>,
        prompt: String,
        backend: TranscriptionBackend,
        audioDurationSeconds: Int,
        chunkProcessingStartTime: Long,
        queuePosition: Int,
        queueTotal: Int,
        listener: TranscriptionListener,
        coroutineScope: CoroutineScope,
        transcriptionStartTime: Long
    ): Result<String> {
        val chunkCount = chunks.size
        val completedChunks = AtomicInteger(0)
        val results = arrayOfNulls<String>(chunkCount)

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

        coroutineScope {
            val deferredResults = chunks.mapIndexed { index, chunk ->
                async {
                    chunkSemaphore.acquire()
                    try {
                        val chunkResult = backend.transcribeAudio(prompt = prompt, audioData = chunk)
                        completedChunks.incrementAndGet()
                        chunkResult
                    } finally {
                        chunkSemaphore.release()
                    }
                }
            }

            deferredResults.forEachIndexed { index, deferred ->
                val chunkResult = deferred.await()
                chunkResult.fold(
                    onSuccess = { text ->
                        if (text.isNotBlank()) {
                            results[index] = text.trim()
                        }
                    },
                    onFailure = { error ->
                        throw Exception("Chunk ${index + 1} failed: ${error.message}", error)
                    }
                )
            }
        }

        progressTimerJob.cancel()

        val combinedResult = results.filterNotNull().joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.filterNotNull().size}/$chunkCount chunks")

        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        return if (combinedResult.isBlank()) {
            Result.failure(IllegalStateException("No transcription produced"))
        } else {
            Result.success(combinedResult)
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

    private suspend fun logRequest(
        taskId: String,
        type: LogEntry.Type,
        prompt: String,
        filePath: String? = null,
        audioDurationSeconds: Double = 0.0,
        sourcePackageName: String? = null
    ) {
        logDao.insert(
            LogEntry(
                taskId = taskId,
                type = type,
                status = LogEntry.Status.PENDING,
                prompt = prompt,
                filePath = filePath,
                audioDurationSeconds = audioDurationSeconds,
                sourcePackageName = sourcePackageName
            ).toEntity()
        )
    }

    private suspend fun logSuccess(taskId: String, result: String, durationMs: Long) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.SUCCESS, result = result, durationMs = durationMs
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
    }

    private suspend fun logError(taskId: String, errorMessage: String, durationMs: Long = 0) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(
            status = LogEntry.Status.ERROR, errorMessage = errorMessage, durationMs = durationMs
        ).toEntity())
        preferencesManager.clearPartialTranscriptionState()
        lastPartialSaveMs = 0L
    }

    private suspend fun cancelIfPending(taskId: String, errorMessage: String, durationMs: Long) {
        val entity = logDao.getByTaskId(taskId) ?: return
        if (entity.status == LogEntry.Status.PENDING.name) {
            logDao.update(entity.toLogEntry().copy(
                status = LogEntry.Status.ERROR, errorMessage = errorMessage, durationMs = durationMs
            ).toEntity())
        }
    }

    private suspend fun updateInterimResult(taskId: String, accumulatedText: String) {
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(result = accumulatedText).toEntity())

        val now = System.currentTimeMillis()
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
        val entity = logDao.getByTaskId(taskId) ?: return
        logDao.update(entity.toLogEntry().copy(audioDurationSeconds = audioDurationSeconds).toEntity())
    }

    // ---- Utilities ----

    private suspend fun modelPathForBackend(backendId: String): String =
        when (backendId) {
            WhisperBackend.BACKEND_ID -> preferencesManager.whisperModelPath.first()
            Qwen3AsrBackend.BACKEND_ID -> preferencesManager.qwen3AsrModelPath.first()
            SherpaOnnxBackend.BACKEND_ID -> preferencesManager.parakeetModelPath.first()
            Gemma4GgufBackend.BACKEND_ID -> preferencesManager.ggufModelPath.first()
            else -> preferencesManager.modelPath.first()
        } ?: ""

    internal fun deriveDisplayName(backendId: String, modelPath: String, fallbackName: String?): String {
        val dirName = File(modelPath).name
        return when (backendId) {
            WhisperBackend.BACKEND_ID -> {
                val variant = dirName.removePrefix("sherpa-onnx-whisper-")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                if (variant.isNotEmpty()) "Whisper $variant" else fallbackName ?: "Whisper"
            }
            Qwen3AsrBackend.BACKEND_ID -> {
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

    internal fun isNoModelConfiguredError(error: Throwable): Boolean {
        val msg = error.message ?: return false
        return msg.contains("No ") && msg.contains("model configured")
    }

    internal fun queueAwareAudioLabel(queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing audio ($queuePosition of $queueTotal)…"
        else "Processing audio…"

    internal fun queueAwareChunkLabel(completed: Int, totalChunks: Int, queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) "Processing chunk $completed/$totalChunks ($queuePosition of $queueTotal)…"
        else "Processing chunk $completed/$totalChunks…"
}
