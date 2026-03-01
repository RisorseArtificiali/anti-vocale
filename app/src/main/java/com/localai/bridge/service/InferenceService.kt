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
import com.localai.bridge.manager.LlmManager
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
            // Check if model is ready, auto-load if not
            if (!LlmManager.isReady()) {
                Log.i(TAG, "Model not ready, attempting auto-load")
                
                val modelPath = getSavedModelPath()
                
                if (modelPath.isNullOrBlank()) {
                    val error = "No model configured. Open the app to select a model."
                    logsViewModel.logError(request.taskId, error, System.currentTimeMillis() - startTime)
                    sendErrorReply(request.taskId, "NO_MODEL_CONFIGURED", error)
                    if (isShareRequest) showErrorNotification(error)
                    return
                }
                
                // Validate model file exists
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    val error = "Model file not found: $modelPath"
                    logsViewModel.logError(request.taskId, error, System.currentTimeMillis() - startTime)
                    sendErrorReply(request.taskId, "MODEL_NOT_FOUND", error)
                    if (isShareRequest) showErrorNotification(error)
                    return
                }
                
                // Auto-load the model
                updateNotification("Loading model...")
                Log.i(TAG, "Auto-loading model from: $modelPath")
                
                val loadResult = LlmManager.initialize(applicationContext, modelPath)
                
                loadResult.fold(
                    onSuccess = {
                        Log.i(TAG, "Model auto-loaded successfully")
                        // Apply saved keep-alive timeout
                        val timeout = AppContainer.preferencesManager.keepAliveTimeout.first()
                        LlmManager.setKeepAliveTimeout(timeout)
                    },
                    onFailure = { error ->
                        val errorMsg = "Failed to load model: ${error.message}"
                        logsViewModel.logError(request.taskId, errorMsg, System.currentTimeMillis() - startTime)
                        sendErrorReply(request.taskId, "MODEL_LOAD_FAILED", errorMsg)
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

    private suspend fun processTextRequest(request: PendingRequest): Result<String> {
        Log.d(TAG, "Processing text request: ${request.taskId}")
        updateNotification("Generating text response...")

        val prompt = request.prompt.ifEmpty {
            return Result.failure(IllegalArgumentException("Empty prompt provided"))
        }

        return LlmManager.generateText(prompt)
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

            val chunkResult = LlmManager.generateFromAudio(
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

        val previewText = if (transcriptionText.length > 100) {
            transcriptionText.take(100) + "…"
        } else {
            transcriptionText
        }

        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
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
            .build()

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
