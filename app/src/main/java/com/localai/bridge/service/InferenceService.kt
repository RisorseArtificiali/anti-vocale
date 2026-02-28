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
import com.localai.bridge.receiver.TaskerRequestReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private val isProcessing = MutableStateFlow(false)

    data class PendingRequest(
        val taskId: String,
        val requestType: String,
        val prompt: String,
        val filePath: String?,
        val startTime: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Processing request..."))

        // Extract request parameters
        val request = PendingRequest(
            taskId = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
                ?: "unknown_${System.currentTimeMillis()}",
            requestType = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE) ?: "text",
            prompt = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) ?: "",
            filePath = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)
        )

        // Enqueue request
        requestQueue.add(request)
        Log.i(TAG, "Request enqueued: ${request.taskId}, queue size: ${requestQueue.size}")

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

        try {
            // Check if model is ready
            if (!LlmManager.isReady()) {
                val error = "Model is not loaded. Please load model in the app first."
                logsViewModel.logError(request.taskId, error, System.currentTimeMillis() - startTime)
                sendErrorReply(request.taskId, "MODEL_NOT_LOADED", error)
                return
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
                },
                onFailure = { error ->
                    logsViewModel.logError(request.taskId, error.message ?: "Unknown error", duration)
                    sendErrorReply(request.taskId, "INFERENCE_ERROR", error.message ?: "Unknown error")
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            val duration = System.currentTimeMillis() - startTime
            logsViewModel.logError(request.taskId, e.message ?: "Unknown error", duration)
            sendErrorReply(request.taskId, "PROCESSING_ERROR", e.message ?: "Unknown error")
        }
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
                    if (text.isNotBlank()) {
                        results.add(text.trim())
                    }
                },
                onFailure = { error ->
                    return Result.failure(Exception("Chunk ${index + 1} failed: ${error.message}"))
                }
            )
        }

        // Join results with spaces
        val combinedResult = results.joinToString(" ")
        Log.i(TAG, "Audio transcription complete: ${combinedResult.length} chars")

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
}
