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
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.transcription.Language
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Foreground service for handling inference requests.
 *
 * Delegates all business logic to [TranscriptionOrchestrator] and handles
 * only Android lifecycle concerns: notifications, broadcasts, clipboard,
 * and foreground service management.
 */
@AndroidEntryPoint
class InferenceService : Service(), TranscriptionListener {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var perAppPreferencesManager: PerAppPreferencesManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator

    companion object {
        const val TAG = "InferenceService"
        const val CHANNEL_ID = "inference_channel"
        const val RESULT_CHANNEL_ID = "transcription_result_channel"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002

        private const val CONFIDENCE_MEDIUM_THRESHOLD = 0.5f

        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1

        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val SOURCE_SHARE = "share"

        const val EXTRA_SHARED_URI = "shared_uri"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_BACKEND_OVERRIDE = "backend_override"

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_TRANSCRIPTION"

        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isTranscribing.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private var currentProcessingJob: Job? = null
    private var transcriptionStartTime: Long = 0
    private val pendingCount = AtomicInteger(0)
    private val resultNotificationCounter = AtomicInteger(RESULT_NOTIFICATION_ID)

    data class PendingRequest(
        val taskId: String,
        val requestType: String,
        val prompt: String,
        val filePath: String?,
        val startTime: Long = System.currentTimeMillis(),
        val source: String? = null,
        val sourcePackage: String? = null,
        val backendOverride: String? = null
    )

    // ---- Android Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createResultNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called, action=${intent?.action}")

        if (intent?.action == ACTION_CANCEL) {
            handleCancelRequest()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.processing_audio)))

        val filePath = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)

        val request = PendingRequest(
            taskId = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
                ?: "unknown_${System.currentTimeMillis()}",
            requestType = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE) ?: "text",
            prompt = intent?.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT) ?: "",
            filePath = filePath,
            source = intent?.getStringExtra(EXTRA_SOURCE),
            sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE),
            backendOverride = intent?.getStringExtra(EXTRA_BACKEND_OVERRIDE)
        )

        requestQueue.add(request)
        val queueSize = pendingCount.incrementAndGet()
        Log.i(TAG, "Request enqueued: ${request.taskId}, source=${request.source}, sourcePackage=${request.sourcePackage}, filePath=$filePath")

        if (_isTranscribing.value && queueSize > 1) {
            updateNotificationQueueHint(queueSize)
        }

        processQueue()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    // ---- Queue Management ----

    private fun processQueue() {
        currentProcessingJob = serviceScope.launch {
            if (_isTranscribing.value) {
                Log.d(TAG, "Already processing, request will wait in queue")
                return@launch
            }

            _isTranscribing.value = true
            val totalInBatch = pendingCount.get()
            var currentIndex = 0

            try {
                while (requestQueue.isNotEmpty()) {
                    val request = requestQueue.poll() ?: continue
                    pendingCount.decrementAndGet()
                    currentIndex++

                    // Show queue-aware initial notification
                    val initialText = if (totalInBatch > 1) {
                        getString(R.string.processing_queue_item, currentIndex, totalInBatch)
                    } else {
                        getString(R.string.processing_request, request.requestType)
                    }
                    updateNotification(initialText)

                    transcriptionStartTime = System.currentTimeMillis()

                    orchestrator.processRequest(
                        taskId = request.taskId,
                        requestType = request.requestType,
                        prompt = request.prompt,
                        filePath = request.filePath,
                        source = request.source,
                        sourcePackage = request.sourcePackage,
                        backendOverride = request.backendOverride,
                        queuePosition = currentIndex,
                        queueTotal = totalInBatch,
                        context = applicationContext,
                        cacheDir = cacheDir,
                        listener = this@InferenceService,
                        coroutineScope = this
                    )
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled by user")
                requestQueue.clear()
                pendingCount.set(0)
            } finally {
                _isTranscribing.value = false
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCancelRequest() {
        Log.i(TAG, "Cancel request received")
        currentProcessingJob?.cancel()
        requestQueue.clear()
        pendingCount.set(0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- TranscriptionListener Implementation ----

    override fun onStatusUpdate(message: String) {
        updateNotification(message)
    }

    override fun onIndeterminateProgress(message: String) {
        updateNotificationWithProgress(message, indeterminate = true)
    }

    override fun onProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int
    ) {
        updateNotificationWithSmoothProgress(
            contentText, progressPercent, etaText, durationSeconds, startTimeMillis, pendingCount.get()
        )
    }

    override fun onInterimResult(contentText: String, bigText: String, subText: String) {
        updateNotification(
            contentText = contentText,
            bigText = bigText,
            subText = subText,
            startTimeMillis = transcriptionStartTime
        )
    }

    override fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float?,
        detectedLanguage: String?
    ) {
        sendSuccessReply(taskId, resultText)
        if (isShareRequest) {
            serviceScope.launch {
                autoCopyIfEnabled(resultText, sourcePackage)
                showResultNotification(resultText, sourcePackage, taskId, confidence, detectedLanguage)
            }
        }
    }

    override fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long
    ) {
        sendErrorReply(taskId, errorCode, errorMessage)
        if (isShareRequest) {
            if (isNoModelError) showNoModelNotification()
            else showErrorNotification(errorMessage)
        }
    }

    // ---- Broadcast Replies ----

    private fun sendSuccessReply(taskId: String, resultText: String) {
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_SUCCESS)
            putExtra(TaskerRequestReceiver.EXTRA_RESULT_TEXT, resultText)
        }
        sendBroadcast(replyIntent)
    }

    private fun sendErrorReply(taskId: String, errorCode: String, errorMessage: String) {
        val replyIntent = Intent(TaskerRequestReceiver.ACTION_TASKER_REPLY).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_STATUS, TaskerRequestReceiver.STATUS_ERROR)
            putExtra(TaskerRequestReceiver.EXTRA_ERROR_MESSAGE, "$errorCode: $errorMessage")
        }
        sendBroadcast(replyIntent)
    }

    // ---- Auto-Copy ----

    private suspend fun autoCopyIfEnabled(transcriptionText: String, sourcePackage: String?) {
        val autoCopyEnabled = if (sourcePackage != null) {
            try {
                val prefs = perAppPreferencesManager.getCurrentPreferences(sourcePackage)
                prefs.autoCopy
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using global", e)
                preferencesManager.autoCopyEnabled.first()
            }
        } else {
            preferencesManager.autoCopyEnabled.first()
        }

        if (autoCopyEnabled) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clipboard_label_transcription), transcriptionText)
            clipboardManager.setPrimaryClip(clip)
            Log.i(TAG, "Auto-copied transcription to clipboard (${transcriptionText.length} chars), source=$sourcePackage")

            Handler(Looper.getMainLooper()).post {
                com.antivocale.app.util.ToastCompat.show(
                    this@InferenceService,
                    R.string.copied_to_clipboard
                )
            }
        }
    }

    // ---- Notifications ----

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

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        } else {
            builder.setProgress(maxProgress, progress, indeterminate)
        }

        if (startTimeMillis > 0) {
            builder.setWhen(startTimeMillis)
            builder.setUsesChronometer(true)
        }

        if (subText != null) {
            builder.setSubText(subText)
        } else if (durationSeconds > 0) {
            builder.setSubText(formatDuration(durationSeconds))
        }

        return builder.build()
    }

    private fun updateNotification(
        contentText: String,
        durationSeconds: Int = 0,
        startTimeMillis: Long = 0,
        bigText: String? = null,
        subText: String? = null
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(contentText, durationSeconds = durationSeconds, startTimeMillis = startTimeMillis, bigText = bigText, subText = subText)
        )
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

    private fun updateNotificationQueueHint(queuedCount: Int) {
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

    private suspend fun showResultNotification(
        transcriptionText: String,
        sourcePackage: String?,
        taskId: String,
        confidence: Float?,
        detectedLanguage: String?
    ) {
        val prefs = if (sourcePackage != null) {
            try {
                perAppPreferencesManager.getCurrentPreferences(sourcePackage)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
                com.antivocale.app.data.AppNotificationPreferences.default()
            }
        } else {
            com.antivocale.app.data.AppNotificationPreferences.default()
        }

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

        if (prefs.showShareAction) {
            val useQuickShareBack = prefs.quickShareBack && sourcePackage != null

            if (useQuickShareBack) {
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

        if (prefs.notificationSound != "default" && prefs.notificationSound != "silent") {
            Log.d(TAG, "Custom notification sound: ${prefs.notificationSound} (not yet implemented)")
        }

        // Confidence and language indicator in subtext
        val subTextParts = mutableListOf<String>()
        if (isTruncated) {
            subTextParts.add(getString(R.string.char_counter, 100, transcriptionText.length))
        }
        val langLabel = detectedLanguage?.let { lang ->
            Language.FILTER_ENTRIES.find { it.code == lang }?.let { getString(it.nameResId) }
        }
        if (langLabel != null) {
            subTextParts.add(getString(R.string.detected_language, langLabel))
        }
        if (confidence != null && confidence < CONFIDENCE_MEDIUM_THRESHOLD) {
            subTextParts.add(getString(R.string.confidence_low))
        }
        if (subTextParts.isNotEmpty()) {
            builder.setSubText(subTextParts.joinToString(" · "))
        }

        val notification = builder.build()
        postUniqueNotification(notification, "Showed result notification (${transcriptionText.length} chars), source=$sourcePackage, showShare=${prefs.showShareAction}")
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

    // ---- Notification Helpers ----

    private fun postUniqueNotification(notification: Notification, description: String) {
        val id = resultNotificationCounter.getAndIncrement()
        notificationManager.notify(id, notification)
        Log.i(TAG, "$description (id=$id)")
    }

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

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val cancelPendingIntent by lazy {
        val cancelIntent = Intent(this, InferenceService::class.java).apply {
            action = ACTION_CANCEL
        }
        android.app.PendingIntent.getService(
            this, 0, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
}
