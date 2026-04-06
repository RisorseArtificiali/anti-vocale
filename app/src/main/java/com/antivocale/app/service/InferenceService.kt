package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.MainActivity
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.di.AppContainer
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.WhisperBackend
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for handling inference requests.
 *
 * Manages:
 * - Request queue (FIFO)
 * - Model lifecycle
 * - Audio preprocessing
 * - Response broadcasting
 */
class InferenceService : Service() {

    companion object {
        const val TAG = "InferenceService"
        const val CHANNEL_ID = "inference_channel"
        const val RESULT_CHANNEL_ID = "transcription_result_channel"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002

        // PendingIntent request codes
        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1

        // Extras for share source detection
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val SOURCE_SHARE = "share"

        // Extras for URI-based audio sharing (vs file path)
        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"

        // Action for canceling transcription
        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_TRANSCRIPTION"

        // Parallel transcription settings
        private const val MAX_CONCURRENT_CHUNKS = 2

        /** Transcription state observable by UI without needing a service instance. */
        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isTranscribing.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private var currentProcessingJob: Job? = null

    private var transcriptionStartTime: Long = 0

    // O(1) queue size tracker — avoids ConcurrentLinkedQueue.size traversal on every 200ms timer tick
    private val pendingCount = AtomicInteger(0)

    // Bounds parallel chunk processing to avoid memory/thermal issues on mobile
    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    /** Monotonically increasing counter for unique result notification IDs. */
    private val resultNotificationCounter = AtomicInteger(RESULT_NOTIFICATION_ID)

    data class PendingRequest(
        val taskId: String,
        val requestType: String,
        val prompt: String,
        val filePath: String?,
        val startTime: Long = System.currentTimeMillis(),
        val source: String? = null,
        val sourcePackage: String? = null  // Calling app package name (e.g., com.whatsapp)
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createResultNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called, action=${intent?.action}")

        // Handle cancel action
        if (intent?.action == ACTION_CANCEL) {
            handleCancelRequest()
            return START_NOT_STICKY
        }

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.processing_audio)))

        // File path is already a local path (copied by ShareReceiverActivity before we got here)
        val filePath = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)

        // Extract request parameters
        val request = PendingRequest(
            taskId = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
                ?: "unknown_${System.currentTimeMillis()}",
            requestType = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE) ?: "text",
            prompt = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) ?: "",
            filePath = filePath,
            source = intent?.getStringExtra(EXTRA_SOURCE),
            sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE)
        )

        // Enqueue request
        requestQueue.add(request)
        val queueSize = pendingCount.incrementAndGet()
        Log.i(TAG, "Request enqueued: ${request.taskId}, source=${request.source}, sourcePackage=${request.sourcePackage}, filePath=$filePath")

        // Update foreground notification with queue info when items pile up
        if (_isTranscribing.value && queueSize > 1) {
            updateNotificationQueueHint(queueSize)
        }

        // Process queue
        processQueue()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    private fun processQueue() {
        currentProcessingJob = serviceScope.launch {
            // Only process one at a time
            if (_isTranscribing.value) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            _isTranscribing.value = true

            // Snapshot batch size for queue-aware notifications
            val totalInBatch = pendingCount.get()
            var currentIndex = 0

            try {
                while (requestQueue.isNotEmpty()) {
                    val request = requestQueue.poll() ?: continue
                    pendingCount.decrementAndGet()
                    currentIndex++
                    processRequest(request, currentIndex, totalInBatch)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled by user")
                // Clear any remaining queue on cancel
                requestQueue.clear()
                pendingCount.set(0)
            } finally {
                _isTranscribing.value = false
            }

            // Stop service when queue is empty
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCancelRequest() {
        Log.i(TAG, "Cancel request received")

        // Cancel the current processing job
        currentProcessingJob?.cancel()

        // Clear the queue
        requestQueue.clear()
        pendingCount.set(0)

        // Dismiss notification and stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun processRequest(request: PendingRequest, queuePosition: Int = 1, queueTotal: Int = 1) {
        Log.i(TAG, "Processing request: ${request.taskId} (queue position $queuePosition/$queueTotal)")

        // Show queue-aware initial notification
        val initialText = if (queueTotal > 1) {
            getString(R.string.processing_queue_item, queuePosition, queueTotal)
        } else {
            getString(R.string.processing_request, request.requestType)
        }
        updateNotification(initialText)

        // Log request start
        val logsViewModel = AppContainer.logsViewModel
        logsViewModel.logRequest(
            taskId = request.taskId,
            type = if (request.requestType == "audio") LogEntry.Type.AUDIO else LogEntry.Type.TEXT,
            prompt = request.prompt,
            filePath = request.filePath,
            sourcePackageName = request.sourcePackage
        )

        val startTime = System.currentTimeMillis()
        val isShareRequest = request.source == SOURCE_SHARE

        try {
            // Check if the correct backend is ready, auto-load if not
            // We need to check THREE conditions:
            // 1. A backend is active
            // 2. That backend is ready (loaded)
            // 3. The active backend matches the user's current preference
            //    (prevents stale backend after user switches models)
            val hasBackend = TranscriptionBackendManager.hasActiveBackend()
            val backendReady = TranscriptionBackendManager.getActiveBackend()?.isReady() ?: false
            val preferredBackendId = AppContainer.preferencesManager.transcriptionBackend.first()
            val activeBackendId = TranscriptionBackendManager.getActiveBackend()?.id
            val backendMismatch = hasBackend && activeBackendId != preferredBackendId

            if (!hasBackend || !backendReady || backendMismatch) {
                Log.i(TAG, "Backend needs (re)load (hasBackend=$hasBackend, ready=$backendReady, active=$activeBackendId, preferred=$preferredBackendId)")

                // If backend exists but isn't the right one or isn't ready, unload it first
                if (hasBackend) {
                    Log.i(TAG, "Unloading previous backend: $activeBackendId")
                    TranscriptionBackendManager.unloadActiveBackend()
                }

                Log.i(TAG, "Loading backend from preferences: $preferredBackendId")

                // Load the appropriate backend based on backend ID
                val loadResult = when (preferredBackendId) {
                    SherpaOnnxBackend.BACKEND_ID -> loadSherpaOnnxBackend()
                    WhisperBackend.BACKEND_ID -> loadWhisperBackend()
                    Qwen3AsrBackend.BACKEND_ID -> loadQwen3AsrBackend()
                    else -> loadLlmBackend()
                }

                loadResult.fold(
                    onSuccess = {
                        Log.i(TAG, "Backend auto-loaded successfully: $preferredBackendId")
                        // Apply saved keep-alive timeout
                        val timeout = AppContainer.preferencesManager.keepAliveTimeout.first()
                        TranscriptionBackendManager.setKeepAliveTimeout(timeout)
                    },
                    onFailure = { error ->
                        val errorMsg = getString(R.string.error_load_backend, error.message)
                        logsViewModel.logError(request.taskId, errorMsg, System.currentTimeMillis() - startTime)
                        sendErrorReply(request.taskId, "BACKEND_LOAD_FAILED", errorMsg)
                        if (isShareRequest) {
                            if (isNoModelConfiguredError(error)) {
                                showNoModelNotification()
                            } else {
                                showErrorNotification(errorMsg)
                            }
                        }
                        return
                    }
                )
            }

            val result = when (request.requestType) {
                "audio" -> processAudioRequest(request, queuePosition, queueTotal)
                else -> processTextRequest(request)
            }

            val duration = System.currentTimeMillis() - startTime

            result.fold(
                onSuccess = { response ->
                    logsViewModel.logSuccess(request.taskId, response, duration)
                    sendSuccessReply(request.taskId, response)
                    // Show result notification for share requests
                    if (isShareRequest) {
                        // Auto-copy if enabled (based on per-app preferences)
                        autoCopyIfEnabled(response, request.sourcePackage)
                        // Show result notification with per-app preferences
                        showResultNotification(response, request.sourcePackage, request.taskId)
                    }
                },
                onFailure = { error ->
                    logsViewModel.logError(request.taskId, error.message ?: getString(R.string.logs_unknown_error), duration)
                    sendErrorReply(request.taskId, "INFERENCE_ERROR", error.message ?: getString(R.string.logs_unknown_error))
                    if (isShareRequest) showErrorNotification(error.message ?: getString(R.string.logs_unknown_error))
                }
            )

        } catch (e: CancellationException) {
            Log.i(TAG, "Request ${request.taskId} cancelled")
            // Mark the entry as cancelled if it's still PENDING,
            // so it doesn't stay stuck forever in the logs.
            val duration = System.currentTimeMillis() - startTime
            logsViewModel.cancelIfPending(
                request.taskId,
                getString(R.string.logs_transcription_cancelled),
                duration
            )
            throw e // re-throw so the coroutine is properly cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            logsViewModel.logError(request.taskId, e.message ?: getString(R.string.logs_unknown_error), duration)
            sendErrorReply(request.taskId, "PROCESSING_ERROR", e.message ?: getString(R.string.logs_unknown_error))
            if (isShareRequest) showErrorNotification(e.message ?: getString(R.string.logs_unknown_error))
        }
    }

    /**
     * Gets the saved model path from preferences.
     */
    private suspend fun getSavedModelPath(): String? {
        return AppContainer.preferencesManager.modelPath.first()
    }

    private suspend fun loadLlmBackend(): Result<Unit> {
        val modelPath = AppContainer.preferencesManager.modelPath.first()

        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException(getString(R.string.error_no_llm_model)))
        }

        updateNotificationWithProgress(getString(R.string.loading_model, "LLM"), indeterminate = true)
        Log.i(TAG, "Auto-loading LLM model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
            context = applicationContext,
            config = BackendConfig.LiteRTConfig(modelPath = modelPath)
        )
    }

    /**
     * Common helper for loading any sherpa-onnx based backend.
     * Reads the model path from preferences, validates the directory,
     * and initializes the backend via [TranscriptionBackendManager].
     */
    private suspend fun loadSherpaOnnxModel(
        backendId: String,
        modelPathFlow: kotlinx.coroutines.flow.Flow<String?>,
        label: String,
        language: String = ""
    ): Result<Unit> {
        val modelPath = modelPathFlow.first()

        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException(getString(R.string.error_no_model_configured, label)))
        }

        val modelDir = java.io.File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("$label model directory not found: $modelPath"))
        }

        updateNotificationWithProgress(getString(R.string.loading_model, label), indeterminate = true)
        Log.i(TAG, "Auto-loading $label model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = backendId,
            context = applicationContext,
            config = BackendConfig.SherpaOnnxConfig(
                modelDir = modelPath,
                numThreads = AppContainer.preferencesManager.threadCount.first(),
                language = language
            )
        )
    }

    private suspend fun loadSherpaOnnxBackend() = loadSherpaOnnxModel(
        backendId = SherpaOnnxBackend.BACKEND_ID,
        modelPathFlow = AppContainer.preferencesManager.parakeetModelPath,
        label = "Parakeet"
    )

    private suspend fun loadWhisperBackend() = loadSherpaOnnxModel(
        backendId = WhisperBackend.BACKEND_ID,
        modelPathFlow = AppContainer.preferencesManager.whisperModelPath,
        label = "Whisper",
        language = AppContainer.preferencesManager.transcriptionLanguage.first().let { lang ->
            if (lang == "auto") "" else lang
        }
    )

    private suspend fun loadQwen3AsrBackend() = loadSherpaOnnxModel(
        backendId = Qwen3AsrBackend.BACKEND_ID,
        modelPathFlow = AppContainer.preferencesManager.qwen3AsrModelPath,
        label = "Qwen3-ASR"
    )

    private suspend fun processTextRequest(request: PendingRequest): Result<String> {
        Log.d(TAG, "Processing text request: ${request.taskId}")
        updateNotification(getString(R.string.generating_text))

        val prompt = request.prompt.ifEmpty {
            return Result.failure(IllegalArgumentException("Empty prompt provided"))
        }

        val backend = TranscriptionBackendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException(getString(R.string.error_no_active_backend)))

        if (!backend.supportsText) {
            return Result.failure(IllegalStateException(
                "Current backend (${backend.displayName}) does not support text generation. " +
                "Switch to LLM backend in Settings."
            ))
        }

        return backend.generateText(prompt)
    }

    private suspend fun processAudioRequest(request: PendingRequest, queuePosition: Int = 1, queueTotal: Int = 1): Result<String> {
        Log.d(TAG, "Processing audio request: ${request.taskId}")

        val filePath = request.filePath
        if (filePath.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException(getString(R.string.error_no_file_path)))
        }

        // Check file exists
        val audioFile = java.io.File(filePath)
        if (!audioFile.exists()) {
            return Result.failure(PreprocessingError.FileNotFound)
        }

        val backend = TranscriptionBackendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException(getString(R.string.error_no_active_backend)))

        // Show indeterminate progress during preprocessing
        updateNotificationWithProgress(getString(R.string.preprocessing_audio), indeterminate = true)

        // Preprocess audio with proper error handling
        // Pass backend's max chunk duration and VAD toggle to AudioPreprocessor
        val vadEnabled = AppContainer.preferencesManager.vadEnabled.first()
        val threadCount = AppContainer.preferencesManager.threadCount.first()
        val preprocessingResult = try {
            AudioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = cacheDir,
                maxChunkDurationSeconds = backend.maxChunkDurationSeconds,
                context = applicationContext,
                enableVad = vadEnabled,
                vadNumThreads = threadCount
            )
        } catch (e: PreprocessingError) {
            Log.e(TAG, "Audio preprocessing error", e)
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected preprocessing error", e)
            return Result.failure(IllegalStateException("Audio preprocessing failed: ${e.message}"))
        }

        val chunkCount = preprocessingResult.chunkCount
        val audioDurationSeconds = preprocessingResult.totalDurationSeconds.toInt()
        Log.i(TAG, "Audio preprocessed: $chunkCount chunks, ${audioDurationSeconds}s")

        // Update log entry with audio duration
        AppContainer.logsViewModel.updateAudioDuration(request.taskId, preprocessingResult.totalDurationSeconds)

        // Track transcription start time for chronometer
        transcriptionStartTime = System.currentTimeMillis()

        // Mark when actual chunk processing begins (after preprocessing) for calibration
        val chunkProcessingStartTime = System.currentTimeMillis()

        // Process chunks in parallel (bounded by semaphore) and concatenate results
        // Use request prompt -> default prompt from settings -> hardcoded fallback
        val savedDefaultPrompt = AppContainer.preferencesManager.defaultPrompt.first()
        val prompt = request.prompt.ifEmpty {
            savedDefaultPrompt.ifEmpty { getString(R.string.default_system_prompt) }
        }
        Log.i(TAG, "=== TRANSCRIPTION DEBUG ===")
        Log.i(TAG, "Request prompt: '${request.prompt}'")
        Log.i(TAG, "Saved default prompt: '$savedDefaultPrompt'")
        Log.i(TAG, "Final prompt: '$prompt'")
        Log.i(TAG, "Active backend: ${TranscriptionBackendManager.getActiveBackend()?.displayName}")
        Log.i(TAG, "===========================")

        // Fast path: single chunk — skip parallel chunking machinery
        if (chunkCount == 1) {
            Log.i(TAG, "Single chunk detected, using fast path")
            updateNotificationWithProgress(getString(R.string.processing_audio), indeterminate = true)

            val result = backend.transcribeAudio(
                prompt = prompt,
                audioData = preprocessingResult.chunks.first()
            )

            return when {
                result.isSuccess && result.getOrNull()?.isNotBlank() == true -> {
                    recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)
                    Result.success(result.getOrNull()!!.trim())
                }
                result.isFailure -> {
                    Log.e(TAG, "Fast path transcription failed", result.exceptionOrNull())
                    Result.failure(result.exceptionOrNull()!!)
                }
                else -> Result.failure(IllegalStateException(getString(R.string.error_no_transcription_produced)))
            }
        }

        // Progressive path: VAD-segmented audio + progressive toggle enabled
        val progressiveEnabled = AppContainer.preferencesManager.progressiveTranscription.first()
        if (preprocessingResult.isVadSegmented && progressiveEnabled) {
            Log.i(TAG, "VAD-segmented progressive path: $chunkCount segments")
            val accumulatedText = StringBuilder()
            var failedSegments = 0

            for (i in preprocessingResult.chunks.indices) {
                val segNumber = i + 1
                // Only show segment progress as contentText before first result;
                // once we have preview text, keep latest segment in contentText for OEM compatibility
                if (accumulatedText.isEmpty()) {
                    updateNotification(getString(R.string.progressive_initial))
                }

                val segResult = backend.transcribeAudio(
                    prompt = prompt,
                    audioData = preprocessingResult.chunks[i]
                )

                segResult.fold(
                    onSuccess = { text ->
                        if (text.isNotBlank()) {
                            val trimmed = text.trim()
                            if (accumulatedText.isNotEmpty()) accumulatedText.append(' ')
                            accumulatedText.append(trimmed)
                            AppContainer.logsViewModel.updateInterimResult(request.taskId, accumulatedText.toString())
                            // Show only the latest segment in the notification (contentText is capped ~100 chars on some OEMs).
                            // The full accumulated text is visible in the app's LogsTab.
                            val latestSegment = text.trim()
                            Log.i(TAG, "Progressive preview update: segment ${latestSegment.length} chars, total ${accumulatedText.length} chars")
                            updateNotification(
                                contentText = latestSegment,
                                durationSeconds = audioDurationSeconds,
                                startTimeMillis = transcriptionStartTime,
                                bigText = latestSegment,
                                subText = getString(R.string.processing_segment_progress, segNumber, chunkCount)
                            )
                            Log.d(TAG, "Segment $segNumber/$chunkCount transcribed (${text.length} chars)")
                        } else {
                            Log.w(TAG, "Segment $segNumber/$chunkCount returned blank, skipping")
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
                val combined = accumulatedText.toString()
                if (failedSegments > 0) {
                    Log.w(TAG, "Completed with $failedSegments/$chunkCount failed segments")
                }
                Result.success(combined)
            }
        }

        // Multi-chunk path: parallel processing with progress tracking
        val completedChunks = AtomicInteger(0)
        val results = arrayOfNulls<String>(chunkCount)

        Log.i(TAG, "Processing $chunkCount chunks with up to $MAX_CONCURRENT_CHUNKS concurrent transcriptions")

        // Get calibration data for the active backend + model variant
        val backendId = backend.id
        val modelPath = when (backendId) {
            WhisperBackend.BACKEND_ID -> AppContainer.preferencesManager.whisperModelPath.first()
            Qwen3AsrBackend.BACKEND_ID -> AppContainer.preferencesManager.qwen3AsrModelPath.first()
            SherpaOnnxBackend.BACKEND_ID -> AppContainer.preferencesManager.parakeetModelPath.first()
            else -> AppContainer.preferencesManager.modelPath.first()
        } ?: ""
        val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)

        val calibrationProfile = AppContainer.transcriptionCalibrator.getEstimate(backendId, modelPath)
        val chunkDurationSeconds = (preprocessingResult.totalDurationSeconds / chunkCount).toLong()
        val estimatedChunkDurationMs = calibrationProfile?.let {
            if (it.hasEstimate) (it.msPerSecondOfAudio * chunkDurationSeconds).toLong() else null
        }

        // Total estimated wall-clock time accounts for parallel batches:
        // e.g. 4 chunks with 2 parallel = 2 batches × chunkDuration
        val estimatedTotalMs = estimatedChunkDurationMs?.let { est ->
            val batches = ceilDiv(chunkCount, MAX_CONCURRENT_CHUNKS).toLong()
            batches * est
        }

        // Start a SINGLE global progress timer (not per-chunk) to avoid
        // competing timers fighting over the same notification when chunks run in parallel.
        val progressTimerJob = serviceScope.launch {
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
                chunkDurationSeconds = chunkDurationSeconds
            )
        }

        coroutineScope {
            val deferredResults = preprocessingResult.chunks.mapIndexed { index, chunk ->
                async {
                    chunkSemaphore.acquire()
                    try {
                        val chunkNumber = index + 1
                        Log.d(TAG, "Processing chunk $chunkNumber/$chunkCount")

                        val chunkResult = backend.transcribeAudio(prompt = prompt, audioData = chunk)

                        completedChunks.incrementAndGet()
                        Log.d(TAG, "Chunk $chunkNumber complete, total progress: ${completedChunks.get()}/$chunkCount")

                        chunkResult
                    } finally {
                        chunkSemaphore.release()
                    }
                }
            }

            // Await all results and collect
            deferredResults.forEachIndexed { index, deferred ->
                val chunkResult = deferred.await()
                val chunkNumber = index + 1
                chunkResult.fold(
                    onSuccess = { text ->
                        if (text.isNotBlank()) {
                            results[index] = text.trim()
                            Log.d(TAG, "Chunk $chunkNumber result: '${text.take(50)}...' (${text.length} chars)")
                        } else {
                            Log.w(TAG, "Chunk $chunkNumber returned blank result, skipping")
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Chunk $chunkNumber failed", error)
                        throw Exception("Chunk $chunkNumber failed: ${error.message}", error)
                    }
                )
            }
        }

        // Stop the global progress timer
        progressTimerJob.cancel()

        // Join results with spaces, filtering nulls
        val combinedResult = results.filterNotNull().joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.filterNotNull().size}/$chunkCount chunks")

        // Record calibration data (only transcription time, excluding preprocessing)
        recordCalibration(backend, audioDurationSeconds, chunkProcessingStartTime)

        if (combinedResult.isBlank()) {
            return Result.failure(IllegalStateException("No transcription produced"))
        }

        return Result.success(combinedResult)
    }

    private fun sendSuccessReply(taskId: String, resultText: String) {
        val duration = System.currentTimeMillis()
        Log.i(TAG, "Sending success reply for task: $taskId (${resultText.length} chars)")

        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_SUCCESS)
            putExtra(TaskerRequestReceiver.EXTRA_RESULT_TEXT, resultText)
        }

        sendBroadcast(replyIntent)
    }

    private fun sendErrorReply(taskId: String, errorCode: String, errorMessage: String) {
        Log.e(TAG, "Sending error reply for task: $taskId - $errorMessage")

        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_ERROR)
            putExtra(TaskerRequestReceiver.EXTRA_ERROR_MESSAGE, "$errorCode: $errorMessage")
        }

        sendBroadcast(replyIntent)
    }

    private suspend fun recordCalibration(
        backend: com.antivocale.app.transcription.TranscriptionBackend,
        audioDurationSeconds: Int,
        startTimeMs: Long
    ) {
        val totalProcessingTimeMs = System.currentTimeMillis() - startTimeMs
        try {
            val backendId = backend.id
            val modelPath = when (backendId) {
                WhisperBackend.BACKEND_ID -> AppContainer.preferencesManager.whisperModelPath.first()
                Qwen3AsrBackend.BACKEND_ID -> AppContainer.preferencesManager.qwen3AsrModelPath.first()
                SherpaOnnxBackend.BACKEND_ID -> AppContainer.preferencesManager.parakeetModelPath.first()
                else -> AppContainer.preferencesManager.modelPath.first()
            } ?: ""
            val modelDisplayName = deriveDisplayName(backendId, modelPath, backend.displayName)

            AppContainer.transcriptionCalibrator.record(
                backendId = backendId,
                modelPath = modelPath,
                displayName = modelDisplayName,
                audioDurationSeconds = audioDurationSeconds.toLong(),
                processingTimeMs = totalProcessingTimeMs
            )
            Log.d(TAG, "Recorded calibration: backend=$backendId, model=$modelPath, ${audioDurationSeconds}s audio in ${totalProcessingTimeMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record calibration", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_inference),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_inference_description)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0,
        bigText: String? = null,
        subText: String? = null
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )

        // Show expandable transcription preview when provided
        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            // Suppress progress bar when showing text preview
        } else {
            builder.setProgress(maxProgress, progress, indeterminate)
        }

        // Show elapsed time chronometer if start time provided
        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        // Show subtext if provided (e.g., segment progress), otherwise show duration
        if (subText != null) {
            builder.setSubText(subText)
        } else if (durationSeconds > 0) {
            builder.setSubText(formatDuration(durationSeconds))
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String, durationSeconds: Int = 0, startTimeMillis: Long = 0, bigText: String? = null, subText: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, durationSeconds = durationSeconds, startTimeMillis = startTimeMillis, bigText = bigText, subText = subText))
    }

    private fun updateNotificationWithProgress(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(contentText, progress, maxProgress, indeterminate, durationSeconds, startTimeMillis)
        )
    }

    /**
     * Formats duration in seconds to MM:SS or HH:MM:SS format.
     */
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    /**
     * Derives a human-readable display name for the model.
     * For Whisper models, extracts the variant from the directory name (e.g., "sherpa-onnx-whisper-turbo" → "Whisper Turbo").
     * Falls back to backend displayName for other types.
     */
    private fun deriveDisplayName(backendId: String, modelPath: String, fallbackName: String?): String {
        val dirName = java.io.File(modelPath).name
        return when (backendId) {
            WhisperBackend.BACKEND_ID -> {
                // Pattern: sherpa-onnx-whisper-{variant}
                val variant = dirName.removePrefix("sherpa-onnx-whisper-")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
                if (variant.isNotEmpty()) "Whisper $variant" else fallbackName ?: "Whisper"
            }
            Qwen3AsrBackend.BACKEND_ID -> {
                // Pattern: sherpa-onnx-qwen3-asr-{variant}
                dirName.removePrefix("sherpa-onnx-qwen3-asr-")
                    .replace("-int8", "")
                    .replace("-", " ")
                    .replaceFirstChar { it.uppercase() }
            }
            else -> fallbackName ?: backendId
        }
    }

    /**
     * Formats remaining seconds into a human-readable ETA string.
     * Shows different formats based on confidence level.
     */
    private fun formatEta(remainingSeconds: Long, confidence: TranscriptionCalibrator.CalibrationProfile.Confidence): String {
        if (remainingSeconds <= 0) return ""

        return when (confidence) {
            TranscriptionCalibrator.CalibrationProfile.Confidence.HIGH -> {
                when {
                    remainingSeconds < 60 -> getString(R.string.transcription_eta_seconds, remainingSeconds)
                    remainingSeconds < 3600 -> {
                        val min = remainingSeconds / 60
                        val sec = remainingSeconds % 60
                        getString(R.string.transcription_eta_minutes, min, sec)
                    }
                    else -> {
                        val hr = remainingSeconds / 3600
                        val min = (remainingSeconds % 3600) / 60
                        getString(R.string.transcription_eta_hours, hr, min)
                    }
                }
            }
            TranscriptionCalibrator.CalibrationProfile.Confidence.LOW -> {
                val min = remainingSeconds / 60
                val sec = remainingSeconds % 60
                getString(R.string.transcription_eta_estimating, min, sec)
            }
            else -> ""
        }
    }

    /** Integer ceiling division: ceil(a / b) without floating point. */
    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /** Cached NotificationManager — avoids repeated getSystemService() every 200ms. */
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /** Cached cancel PendingIntent — avoids recreating every 200ms. */
    private val cancelPendingIntent by lazy {
        val cancelIntent = Intent(this, InferenceService::class.java).apply {
            action = ACTION_CANCEL
        }
        android.app.PendingIntent.getService(
            this, 0, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Single global progress timer that runs for the entire transcription.
     *
     * Unlike per-chunk timers, this correctly handles parallel chunk processing:
     * - One timer, one notification updater — no competing updates
     * - Progress accounts for parallel batches (e.g. 4 chunks / 2 parallel = 2 batches)
     * - Hard progress (completed chunks) provides a floor; time-based estimation fills gaps
     * - ETA uses total estimated time accounting for parallelism
     */
    private fun CoroutineScope.startGlobalProgressTimer(
        totalChunks: Int,
        completedChunks: AtomicInteger,
        estimatedTotalMs: Long?,
        audioDurationSeconds: Int,
        calibrationProfile: TranscriptionCalibrator.CalibrationProfile?,
        queuePosition: Int = 1,
        queueTotal: Int = 1,
        backendId: String = "",
        modelPath: String = "",
        modelDisplayName: String = "",
        chunkDurationSeconds: Long = 0
    ): Job {
        val startTime = System.currentTimeMillis()
        var lastProgressPercent = -1
        val totalBatches = ceilDiv(totalChunks, MAX_CONCURRENT_CHUNKS)
        val singleChunkLabel = if (totalChunks == 1) {
            queueAwareAudioLabel(queuePosition, queueTotal)
        } else null

        // Feedback loop state: track actual batch completions to adapt ETA
        var lastBatchCompletedCount = 0
        var lastBatchElapsedMs: Long? = null  // elapsed time when last batch completed
        var measuredAvgBatchMs: Long? = null   // per-batch wall-clock time (latest delta)
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
                    // Per-batch delta: time since last batch (or total elapsed for first batch)
                    val prevBatchElapsed = lastBatchElapsedMs
                    measuredAvgBatchMs = if (prevBatchElapsed != null) {
                        elapsedMs - prevBatchElapsed
                    } else {
                        elapsedMs
                    }
                    lastBatchElapsedMs = elapsedMs
                    lastBatchCompletedCount = completedBatches

                    Log.d(TAG, "ETA feedback: batch $completedBatches/$totalBatches in ${measuredAvgBatchMs}ms")

                    // Nudge calibrator after first batch so interrupted transcriptions still help
                    if (!firstBatchRecorded && backendId.isNotEmpty()) {
                        firstBatchRecorded = true
                        val batchAudioSeconds = chunkDurationSeconds * MAX_CONCURRENT_CHUNKS
                        val batchMs = measuredAvgBatchMs!!  // non-null after assignment above
                        launch {
                            try {
                                AppContainer.transcriptionCalibrator.record(
                                    backendId = backendId,
                                    modelPath = modelPath,
                                    displayName = modelDisplayName,
                                    audioDurationSeconds = batchAudioSeconds,
                                    processingTimeMs = batchMs
                                )
                                Log.d(TAG, "Mid-transcription calibration: ${batchAudioSeconds}s audio in ${batchMs}ms")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed mid-transcription calibration", e)
                            }
                        }
                    }
                }

                // Hard progress floor: completed chunks / total
                val hardPercent = completed * 100 / totalChunks

                // Adaptive ETA: use measured batch times when available, historical estimate otherwise
                val adaptiveEtaMs: Long? = if (measuredAvgBatchMs != null) {
                    val remainingBatches = ceilDiv(totalChunks - completed, MAX_CONCURRENT_CHUNKS).toLong()
                    remainingBatches * measuredAvgBatchMs
                } else if (estimatedTotalMs != null) {
                    maxOf(0L, estimatedTotalMs - elapsedMs)
                } else null

                // Time-based estimated progress using adaptive ETA
                val timePercent = if (adaptiveEtaMs != null && adaptiveEtaMs > 0) {
                    (elapsedMs.toFloat() / (elapsedMs + adaptiveEtaMs) * 100f).toInt().coerceIn(0, 95)
                } else {
                    // No calibration: slow crawl to 80% over ~2× audio duration
                    val crawlTarget = audioDurationSeconds * 1000f * 2f
                    (elapsedMs / crawlTarget * 80f).toInt().coerceIn(0, 80)
                }

                // Display the higher of hard vs estimated, minimum 1% for visible feedback
                val displayProgress = maxOf(1, hardPercent, timePercent).coerceIn(0, 99)

                // Skip if unchanged
                if (displayProgress == lastProgressPercent) continue
                lastProgressPercent = displayProgress

                // ETA text: show adaptive estimate or calibration status
                val etaText = adaptiveEtaMs?.let { eta ->
                    if (measuredAvgBatchMs != null) {
                        val confidence = calibrationProfile?.confidence
                            ?: TranscriptionCalibrator.CalibrationProfile.Confidence.LOW
                        formatEta(eta / 1000, confidence)
                    } else if (calibrationProfile != null && calibrationProfile.hasEstimate) {
                        formatEta(eta / 1000, calibrationProfile.confidence)
                    } else null
                } ?: if (calibrationProfile != null && !calibrationProfile.hasEstimate) {
                    // Not enough samples yet — show calibrating message
                    getString(R.string.transcription_eta_calibrating)
                } else {
                    ""
                }

                val contentText = if (completed == 0 && totalChunks > 1) {
                    queueAwareAudioLabel(queuePosition, queueTotal)
                } else {
                    singleChunkLabel ?: queueAwareChunkLabel(completed, totalChunks, queuePosition, queueTotal)
                }

                updateNotificationWithSmoothProgress(
                    contentText = contentText,
                    progressPercent = displayProgress,
                    etaText = etaText,
                    durationSeconds = audioDurationSeconds,
                    startTimeMillis = transcriptionStartTime,
                    queuedCount = pendingCount.get()
                )
            }
        }
    }

    /**
     * Returns a queue-aware label for "Processing audio…".
     * When multiple items are queued, includes position: "Processing audio (2 of 5)…"
     */
    private fun queueAwareAudioLabel(queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) getString(R.string.processing_queue_item_audio, queuePosition, queueTotal)
        else getString(R.string.processing_audio)

    /**
     * Returns a queue-aware label for chunk progress.
     * When multiple items are queued, includes position: "Processing chunk 1/3 (2 of 5)…"
     */
    private fun queueAwareChunkLabel(completed: Int, totalChunks: Int, queuePosition: Int, queueTotal: Int): String =
        if (queueTotal > 1) getString(R.string.processing_queue_chunk, completed, totalChunks, queuePosition, queueTotal)
        else getString(R.string.processing_chunk, completed, totalChunks)

    /**
     * Posts a notification with a unique ID from the result counter and logs it.
     */
    private fun postUniqueNotification(notification: Notification, description: String) {
        val id = resultNotificationCounter.getAndIncrement()
        notificationManager.notify(id, notification)
        Log.i(TAG, "$description (id=$id)")
    }

    /**
     * Updates the foreground notification subtext to show how many items are queued.
     * Called when a new share arrives while transcription is already active.
     */
    private fun updateNotificationQueueHint(queuedCount: Int) {
        // Build a notification that keeps the current progress state but adds queue info to subtext
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.queued_count, queuedCount))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, 0, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        Log.i(TAG, "Updated notification with queue hint: $queuedCount queued")
    }

    private fun updateNotificationWithSmoothProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int = 0
    ) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progressPercent, false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel), cancelPendingIntent
            )

        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        // Build subtext: queue info + ETA/duration
        val queueText = if (queuedCount > 0) getString(R.string.queued_count, queuedCount) else ""
        val timingText = when {
            etaText.isNotEmpty() -> etaText
            durationSeconds > 0 -> formatDuration(durationSeconds)
            else -> ""
        }
        val subText = when {
            queueText.isNotEmpty() && timingText.isNotEmpty() -> "$queueText · $timingText"
            queueText.isNotEmpty() -> queueText
            timingText.isNotEmpty() -> timingText
            else -> null
        }
        if (subText != null) {
            builder.setSubText(subText)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Auto-copies transcription to clipboard if the setting is enabled.
     * Shows a toast notification to the user.
     */
    private suspend fun autoCopyIfEnabled(transcriptionText: String, sourcePackage: String?) {
        // Get per-app preferences, fallback to global preference
        val autoCopyEnabled = if (sourcePackage != null) {
            try {
                val prefs = AppContainer.perAppPreferencesManager.getCurrentPreferences(sourcePackage)
                prefs.autoCopy
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using global", e)
                AppContainer.preferencesManager.autoCopyEnabled.first()
            }
        } else {
            AppContainer.preferencesManager.autoCopyEnabled.first()
        }

        if (autoCopyEnabled) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clipboard_label_transcription), transcriptionText)
            clipboardManager.setPrimaryClip(clip)
            Log.i(TAG, "Auto-copied transcription to clipboard (${transcriptionText.length} chars), source=$sourcePackage")

            // Show toast on main thread
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this@InferenceService,
                    R.string.copied_to_clipboard,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun createResultNotificationChannel() {
        val channel = NotificationChannel(
            RESULT_CHANNEL_ID,
            getString(R.string.notification_channel_result),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_result_description)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun showResultNotification(transcriptionText: String, sourcePackage: String?, taskId: String) {
        // Get per-app preferences
        val prefs = if (sourcePackage != null) {
            try {
                AppContainer.perAppPreferencesManager.getCurrentPreferences(sourcePackage)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
                com.antivocale.app.data.AppNotificationPreferences.default()
            }
        } else {
            com.antivocale.app.data.AppNotificationPreferences.default()
        }

        // Copy action
        val copyIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, transcriptionText)
        }
        val copyPendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            System.currentTimeMillis().toInt(),
            copyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val openPendingIntent = buildLaunchPendingIntent(highlightTaskId = taskId)

        val isTruncated = transcriptionText.length > 100
        val previewText = if (isTruncated) {
            transcriptionText.take(100) + "…"
        } else {
            transcriptionText
        }

        val builder = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.transcription_complete))
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(transcriptionText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_save,
                getString(R.string.copy),
                copyPendingIntent
            )
            .setAutoCancel(true)

        // Conditionally add share action based on per-app preferences
        if (prefs.showShareAction) {
            // Check if we should use Quick Share Back (direct app open) or regular share sheet
            val useQuickShareBack = prefs.quickShareBack && sourcePackage != null

            if (useQuickShareBack) {
                // Quick Share Back: Show only "Send to [App]" button (bypasses chooser)
                val shareBackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcriptionText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val targetPackage = when {
                        sourcePackage == "com.whatsapp" || sourcePackage.startsWith("com.whatsapp") -> "com.whatsapp"
                        sourcePackage == "org.telegram.messenger" || sourcePackage.startsWith("org.telegram") -> "org.telegram.messenger"
                        sourcePackage == "org.thoughtcrime.securesms" -> "org.thoughtcrime.securesms"
                        else -> sourcePackage
                    }
                    setPackage(targetPackage)
                }
                val shareBackPendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    System.currentTimeMillis().toInt() + 1,
                    shareBackIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_revert,
                    AppInfoUtils.getSendToText(this, sourcePackage),
                    shareBackPendingIntent
                )
            } else {
                // Regular Share: Show chooser to pick destination app
                val shareChooserIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcriptionText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val sharePickerIntent = Intent.createChooser(shareChooserIntent, getString(R.string.share_transcription)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val sharePendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    System.currentTimeMillis().toInt() + 1,
                    sharePickerIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_share,
                    getString(R.string.share),
                    sharePendingIntent
                )
            }
        }

        // Set notification sound (can be extended with custom sound URIs)
        if (prefs.notificationSound != "default" && prefs.notificationSound != "silent") {
            Log.d(TAG, "Custom notification sound: ${prefs.notificationSound} (not yet implemented)")
        }

        // Show character counter in subtext when content is truncated
        if (isTruncated) {
            builder.setSubText(getString(R.string.char_counter, 100, transcriptionText.length))
        }

        val notification = builder.build()

        postUniqueNotification(notification, "Showed result notification (${transcriptionText.length} chars), source=$sourcePackage, showShare=${prefs.showShareAction}")
    }

    /**
     * Builds a [PendingIntent] that launches the app's main activity.
     *
     * @param navigateToModelTab when true, passes an extra so the activity
     *   opens on the Model tab instead of the default Logs tab.
     */
    private fun buildLaunchPendingIntent(
        navigateToModelTab: Boolean = false,
        highlightTaskId: String? = null
    ): android.app.PendingIntent {
        val requestCode = when {
            highlightTaskId != null -> highlightTaskId.hashCode()
            navigateToModelTab -> RC_LAUNCH_MODEL_TAB
            else -> RC_LAUNCH_DEFAULT
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            if (highlightTaskId != null) {
                // SINGLE_TOP allows onNewIntent() when activity already exists
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, highlightTaskId)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (navigateToModelTab) {
                putExtra(MainActivity.EXTRA_NAVIGATE_TO_MODEL_TAB, true)
            }
        }
        return android.app.PendingIntent.getActivity(
            this, requestCode, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showErrorNotification(errorMessage: String) {
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.transcription_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(buildLaunchPendingIntent())
            .setAutoCancel(true)
            .build()

        postUniqueNotification(notification, "Showed error notification: $errorMessage")
    }

    /**
     * Returns true if the error indicates that no model is configured,
     * which should trigger a guidance notification instead of a generic error.
     */
    private fun isNoModelConfiguredError(error: Throwable): Boolean {
        val msg = error.message ?: return false
        return msg.contains("No ") && msg.contains("model configured")
    }

    /**
     * Shows a user-friendly notification when no transcription model is configured,
     * with an action button to open the app to the Model tab.
     */
    private fun showNoModelNotification() {
        val openPendingIntent = buildLaunchPendingIntent(navigateToModelTab = true)

        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_no_model_title))
            .setContentText(getString(R.string.notification_no_model_message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_set_as,
                getString(R.string.notification_no_model_action),
                openPendingIntent
            )
            .build()

        postUniqueNotification(notification, "Showed no-model notification")
    }
}
