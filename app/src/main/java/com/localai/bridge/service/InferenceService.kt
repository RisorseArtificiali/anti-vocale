package com.localai.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localai.bridge.R
import com.localai.bridge.audio.AudioPreprocessor
import com.localai.bridge.audio.AudioPreprocessor.PreprocessingError
import com.localai.bridge.di.AppContainer
import com.localai.bridge.transcription.BackendConfig
import com.localai.bridge.transcription.TranscriptionBackendManager
import com.localai.bridge.ui.viewmodel.LogEntry
import com.localai.bridge.receiver.NotificationActionReceiver
import com.localai.bridge.receiver.TaskerRequestReceiver
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
        const val SOURCE_SHARE = "share"

        // Extras for URI-based audio sharing (vs file path)
        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private val isProcessing = MutableStateFlow(false)

    data class PendingRequest(
        val taskId: String,
        val requestType: String,
        val prompt: String,
        val filePath: String?,
        val startTime: Long = System.currentTimeMillis(),
        val source: String? = null
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createResultNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")

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
            source = intent?.getStringExtra(EXTRA_SOURCE)
        )

        // Enqueue request
        requestQueue.add(request)
        Log.i(TAG, "Request enqueued: ${request.taskId}, source=${request.source}, filePath=$filePath")

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
        serviceScope.launch {
            // Only process one at a time
            if (isProcessing.value) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            isProcessing.value = true

            while (requestQueue.isNotEmpty()) {
                val request = requestQueue.poll() ?: continue
                processRequest(request)
            }

            isProcessing.value = false

            // Stop service when queue is empty
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun processRequest(request: PendingRequest) {
        Log.i(TAG, "Processing request: ${request.taskId}")
        updateNotification("Processing ${request.requestType} request...")

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
                    if (isShareRequest) showResultNotification(response)
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

    /**
     * Loads the LLM (LiteRT-LM) backend with the saved model path.
     */
    private suspend fun loadLlmBackend(): Result<Unit> {
        val modelPath = AppContainer.preferencesManager.modelPath.first()

        if (modelPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No LLM model configured. Open the app to select a model."))
        }

        // Validate model file exists
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            return Result.failure(IllegalStateException("LLM model file not found: $modelPath"))
        }

        updateNotification("Loading LLM model...")
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

        updateNotification("Loading Parakeet model...")
        Log.i(TAG, "Auto-loading Parakeet model from: $modelPath")

        return TranscriptionBackendManager.setActiveBackend(
            backendId = "sherpa-onnx",
            context = applicationContext,
            config = BackendConfig.SherpaOnnxConfig(modelDir = modelPath)
        )
    }

    private suspend fun processTextRequest(request: PendingRequest): Result<String> {
        Log.d(TAG, "Processing text request: ${request.taskId}")
        updateNotification("Generating text response...")

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

        updateNotification("Preprocessing audio...")

        // Preprocess audio with proper error handling
        val preprocessingResult = try {
            AudioPreprocessor.prepareAudioForMediaPipe(
                inputPath = filePath,
                cacheDir = cacheDir
            )
        } catch (e: PreprocessingError) {
            Log.e(TAG, "Audio preprocessing error", e)
            return Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected preprocessing error", e)
            return Result.failure(IllegalStateException("Audio preprocessing failed: ${e.message}"))
        }

        Log.i(TAG, "Audio preprocessed: ${preprocessingResult.chunkCount} chunks, ${preprocessingResult.totalDurationSeconds}s")

        // Process chunks sequentially and concatenate results
        val results = mutableListOf<String>()
        val prompt = request.prompt.ifEmpty { "Transcribe this speech:" }

        for ((index, chunk) in preprocessingResult.chunks.withIndex()) {
            updateNotification("Processing chunk ${index + 1}/${preprocessingResult.chunkCount}...")
            Log.d(TAG, "Processing chunk ${index + 1}/${preprocessingResult.chunkCount}")

            val chunkResult = backend.transcribeAudio(
                prompt = prompt,
                audioData = chunk
            )

            chunkResult.fold(
                onSuccess = { text ->
                    Log.d(TAG, "Chunk ${index + 1} result: '${text.take(50)}...' (${text.length} chars)")
                    if (text.isNotBlank()) {
                        results.add(text.trim())
                    } else {
                        Log.w(TAG, "Chunk ${index + 1} returned blank result, skipping")
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Chunk ${index + 1} failed", error)
                    return Result.failure(Exception("Chunk ${index + 1} failed: ${error.message}"))
                }
            )
        }

        // Join results with spaces
        val combinedResult = results.joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars from ${results.size}/${preprocessingResult.chunkCount} chunks")

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
            "Inference Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background inference processing"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalAI Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }


    private fun createResultNotificationChannel() {
        val channel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Transcription Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Transcription completion notifications"
            setShowBadge(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun showResultNotification(transcriptionText: String) {
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

        // Show character counter in subtext when content is truncated
        if (isTruncated) {
            builder.setSubText(getString(R.string.char_counter, 100, transcriptionText.length))
        }

        val notification = builder.build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
        Log.i(TAG, "Showed result notification (${transcriptionText.length} chars)")
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
