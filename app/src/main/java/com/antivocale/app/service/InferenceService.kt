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
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.audio.AudioPreprocessor.PreprocessingError
import com.antivocale.app.di.AppContainer
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.ui.viewmodel.LogEntry
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.util.AppInfoUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue

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

        // Extras for share source detection
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val SOURCE_SHARE = "share"

        // Extras for URI-based audio sharing (vs file path)
        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"

        // Action for canceling transcription
        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_TRANSCRIPTION"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private val isProcessing = MutableStateFlow(false)
    private var currentProcessingJob: Job? = null
    private var transcriptionStartTime: Long = 0

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
        startForeground(NOTIFICATION_ID, createNotification("Processing request..."))

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
        Log.i(TAG, "Request enqueued: ${request.taskId}, source=${request.source}, sourcePackage=${request.sourcePackage}, filePath=$filePath")

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
            if (isProcessing.value) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            isProcessing.value = true

            try {
                while (requestQueue.isNotEmpty()) {
                    val request = requestQueue.poll() ?: continue
                    processRequest(request)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled by user")
                // Clear any remaining queue on cancel
                requestQueue.clear()
            } finally {
                isProcessing.value = false
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

        // Dismiss notification and stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun processRequest(request: PendingRequest) {
        Log.i(TAG, "Processing request: ${request.taskId}")
        updateNotification(getString(R.string.processing_request, request.requestType))

        // Log request start
        val logsViewModel = AppContainer.logsViewModel
        logsViewModel.logRequest(
            taskId = request.taskId,
            type = if (request.requestType == "audio") LogEntry.Type.AUDIO else LogEntry.Type.TEXT,
            prompt = request.prompt,
            filePath = request.filePath
        )

        val startTime = System.currentTimeMillis()
        val isShareRequest = request.source == SOURCE_SHARE

        try {
            // Check if backend is ready, auto-load if not
            // We need to check BOTH hasActiveBackend() AND isReady() because
            // after auto-unload, hasActiveBackend() returns true but the model is unloaded
            val hasBackend = TranscriptionBackendManager.hasActiveBackend()
            val backendReady = TranscriptionBackendManager.getActiveBackend()?.isReady() ?: false

            if (!hasBackend || !backendReady) {
                Log.i(TAG, "No active backend or backend not ready, attempting auto-load (hasBackend=$hasBackend, ready=$backendReady)")

                // If backend exists but isn't ready, unload it first
                if (hasBackend && !backendReady) {
                    Log.i(TAG, "Unloading stale backend")
                    TranscriptionBackendManager.unloadActiveBackend()
                }

                // Get the selected backend from preferences
                val backendId = AppContainer.preferencesManager.transcriptionBackend.first()
                Log.i(TAG, "Selected backend from preferences: $backendId")

                // Load the appropriate backend based on backend ID
                val loadResult = when (backendId) {
                    "sherpa-onnx" -> loadSherpaOnnxBackend()
                    "whisper" -> loadWhisperBackend()
                    else -> loadLlmBackend()
                }

                loadResult.fold(
                    onSuccess = {
                        Log.i(TAG, "Backend auto-loaded successfully: $backendId")
                        // Apply saved keep-alive timeout
                        val timeout = AppContainer.preferencesManager.keepAliveTimeout.first()
                        TranscriptionBackendManager.setKeepAliveTimeout(timeout)
                    },
                    onFailure = { error ->
                        val errorMsg = "Failed to load backend: ${error.message}"
                        logsViewModel.logError(request.taskId, errorMsg, System.currentTimeMillis() - startTime)
                        sendErrorReply(request.taskId, "BACKEND_LOAD_FAILED", errorMsg)
                        if (isShareRequest) showErrorNotification(errorMsg)
                        return
                    }
                )
            }

            val result = when (request.requestType) {
                "audio" -> processAudioRequest(request)
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
                        showResultNotification(response, request.sourcePackage)
                    }
                },
                onFailure = { error ->
                    logsViewModel.logError(request.taskId, error.message ?: "Unknown error", duration)
                    sendErrorReply(request.taskId, "INFERENCE_ERROR", error.message ?: "Unknown error")
                    if (isShareRequest) showErrorNotification(error.message ?: "Unknown error")
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            logsViewModel.logError(request.taskId, e.message ?: "Unknown error", duration)
            sendErrorReply(request.taskId, "PROCESSING_ERROR", e.message ?: "Unknown error")
            if (isShareRequest) showErrorNotification(e.message ?: "Unknown error")
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
            return Result.failure(IllegalStateException("No LLM model configured. Open the app to download a model."))
        }

        updateNotificationWithProgress("Loading LLM model...", indeterminate = true)
        Log.i(TAG, "Auto-loading LLM model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = "llm",
            context = applicationContext,
            config = BackendConfig.LiteRTConfig(modelPath = modelPath)
        )
    }

    /**
     * Loads the sherpa-onnx backend with the saved Parakeet model path.
     */
    private suspend fun loadSherpaOnnxBackend(): Result<Unit> {
        val modelPath = AppContainer.preferencesManager.parakeetModelPath.first()

        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No Parakeet model configured. Open the app to download a model."))
        }

        // Validate model directory exists
        val modelDir = java.io.File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("Parakeet model directory not found: $modelPath"))
        }

        updateNotificationWithProgress("Loading Parakeet model...", indeterminate = true)
        Log.i(TAG, "Auto-loading Parakeet model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = "sherpa-onnx",
            context = applicationContext,
            config = BackendConfig.SherpaOnnxConfig(modelDir = modelPath)
        )
    }

    /**
     * Loads the sherpa-onnx backend with the saved Whisper model path.
     */
    private suspend fun loadWhisperBackend(): Result<Unit> {
        val modelPath = AppContainer.preferencesManager.whisperModelPath.first()

        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No Whisper model configured. Open the app to download a model."))
        }

        // Validate model directory exists
        val modelDir = java.io.File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return Result.failure(IllegalStateException("Whisper model directory not found: $modelPath"))
        }

        updateNotificationWithProgress("Loading Whisper model...", indeterminate = true)
        Log.i(TAG, "Auto-loading Whisper model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = "whisper",
            context = applicationContext,
            config = BackendConfig.SherpaOnnxConfig(modelDir = modelPath)
        )
    }

    private suspend fun processTextRequest(request: PendingRequest): Result<String> {
        Log.d(TAG, "Processing text request: ${request.taskId}")
        updateNotification(getString(R.string.generating_text))

        val prompt = request.prompt.ifEmpty {
            return Result.failure(IllegalArgumentException("Empty prompt provided"))
        }

        val backend = TranscriptionBackendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))

        if (!backend.supportsText) {
            return Result.failure(IllegalStateException(
                "Current backend (${backend.displayName}) does not support text generation. " +
                "Switch to LLM backend in Settings."
            ))
        }

        return backend.generateText(prompt)
    }

    private suspend fun processAudioRequest(request: PendingRequest): Result<String> {
        Log.d(TAG, "Processing audio request: ${request.taskId}")

        val filePath = request.filePath
        if (filePath.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("No file path provided for audio request"))
        }

        // Check file exists
        val audioFile = java.io.File(filePath)
        if (!audioFile.exists()) {
            return Result.failure(PreprocessingError.FileNotFound)
        }

        val backend = TranscriptionBackendManager.getActiveBackend()
            ?: return Result.failure(IllegalStateException("No active backend"))

        // Show indeterminate progress during preprocessing
        updateNotificationWithProgress("Preprocessing audio...", indeterminate = true)

        // Preprocess audio with proper error handling
        // Pass backend's max chunk duration to AudioPreprocessor
        val preprocessingResult = try {
            AudioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = cacheDir,
                maxChunkDurationSeconds = backend.maxChunkDurationSeconds
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

        // Process chunks sequentially and concatenate results
        val results = mutableListOf<String>()
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

        for ((index, chunk) in preprocessingResult.chunks.withIndex()) {
            val chunkNumber = index + 1

            // Show determinate progress during chunk processing
            // Only show progress bar if there's more than one chunk
            if (chunkCount > 1) {
                updateNotificationWithProgress(
                    contentText = getString(R.string.processing_chunk, chunkNumber, chunkCount),
                    progress = chunkNumber,
                    maxProgress = chunkCount,
                    indeterminate = false,
                    durationSeconds = audioDurationSeconds,
                    startTimeMillis = transcriptionStartTime
                )
            } else {
                updateNotification(getString(R.string.processing_audio), durationSeconds = audioDurationSeconds, startTimeMillis = transcriptionStartTime)
            }

            Log.d(TAG, "Processing chunk $chunkNumber/$chunkCount")

            val chunkResult = backend.transcribeAudio(
                prompt = prompt,
                audioData = chunk
            )

            chunkResult.fold(
                onSuccess = { text ->
                    Log.d(TAG, "Chunk $chunkNumber result: '${text.take(50)}...' (${text.length} chars)")
                    if (text.isNotBlank()) {
                        results.add(text.trim())
                    } else {
                        Log.w(TAG, "Chunk $chunkNumber returned blank result, skipping")
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Chunk $chunkNumber failed", error)
                    return Result.failure(Exception("Chunk $chunkNumber failed: ${error.message}"))
                }
            )
        }

        // Join results with spaces
        val combinedResult = results.joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.size}/$chunkCount chunks")

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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_inference),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_inference_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0
    ): Notification {
        // Create cancel intent
        val cancelIntent = Intent(this, InferenceService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = android.app.PendingIntent.getService(
            this,
            System.currentTimeMillis().toInt(),
            cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(maxProgress, progress, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )

        // Show elapsed time chronometer if start time provided
        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        // Show duration in subtext if available
        if (durationSeconds > 0) {
            builder.setSubText(formatDuration(durationSeconds))
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String, durationSeconds: Int = 0, startTimeMillis: Long = 0) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, durationSeconds = durationSeconds, startTimeMillis = startTimeMillis))
    }

    private fun updateNotificationWithProgress(
        contentText: String,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)
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
            val clip = ClipData.newPlainText("Transcription", transcriptionText)
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

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun showResultNotification(transcriptionText: String, sourcePackage: String?) {
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

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

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

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
        Log.i(TAG, "Showed result notification (${transcriptionText.length} chars), source=$sourcePackage, showShare=${prefs.showShareAction}")
    }

    private fun showErrorNotification(errorMessage: String) {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(getString(R.string.transcription_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
        Log.i(TAG, "Showed error notification: $errorMessage")
    }
}
