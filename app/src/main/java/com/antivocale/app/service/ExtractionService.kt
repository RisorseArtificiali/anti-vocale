package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.di.AppContainer
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that wraps model download + extraction so it survives
 * screen-off and background interruption.
 *
 * All three downloaders (Parakeet, Whisper, Gemma) are invoked inside
 * [serviceScope], which is not tied to the Activity lifecycle. The ViewModel
 * observes [progressState] to update the UI.
 *
 * Notification follows the same pattern as [InferenceService].
 */
class ExtractionService : Service() {

    companion object {
        const val TAG = "ExtractionService"
        const val CHANNEL_ID = "extraction_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_EXTRACTION"

        const val EXTRA_MODEL_TYPE = "model_type"
        const val EXTRA_VARIANT = "variant"

        /** Shared progress state — ViewModel collects this. */
        private val _progressState = MutableStateFlow<ExtractionProgress?>(null)
        val progressState = _progressState.asStateFlow()

        fun clearProgress() {
            _progressState.value = null
        }
    }

    /** Typed model type identifier — replaces raw strings across Service/ViewModel boundary. */
    enum class ModelType(val key: String) {
        PARAKEET("parakeet"),
        WHISPER("whisper"),
        GEMMA("gemma");

        companion object {
            fun fromKey(key: String?): ModelType? = entries.find { it.key == key }
        }
    }

    data class ExtractionProgress(
        val modelType: ModelType,
        val variant: String? = null,
        val downloadState: DownloadState
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
    private var currentJob: Job? = null
    @Volatile private var isCancelling = false
    private var modelDisplayName: String = ""

    /** Resolves a human-readable model name for the notification. */
    private fun resolveDisplayName(modelType: ModelType, variant: String?): String {
        return when (modelType) {
            ModelType.PARAKEET -> getString(R.string.parakeet_title)
            ModelType.WHISPER -> {
                val wv = WhisperVariant.fromString(variant)
                wv?.let { getString(it.titleResId) } ?: "Whisper"
            }
            ModelType.GEMMA -> GemmaVariant.fromString(variant).displayName
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            handleCancel()
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.w(TAG, "Null intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val modelType = ModelType.fromKey(intent.getStringExtra(EXTRA_MODEL_TYPE)) ?: run {
            Log.w(TAG, "No model_type extra, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val variant = intent.getStringExtra(EXTRA_VARIANT)

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.download_status_connecting)))

        // Cancel any previous job
        currentJob?.cancel()

        currentJob = serviceScope.launch {
            executeDownload(modelType, variant)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        clearProgress()
        Log.i(TAG, "Service destroyed")
    }

    private suspend fun executeDownload(modelType: ModelType, variant: String?) {
        modelDisplayName = resolveDisplayName(modelType, variant)
        try {
            when (modelType) {
                ModelType.PARAKEET -> {
                    ParakeetDownloader.downloadModel(
                        context = applicationContext,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.value = ExtractionProgress(ModelType.PARAKEET, downloadState = state)
                            updateNotificationFromState(state)
                        }
                    )
                }
                ModelType.WHISPER -> {
                    val whisperVariant = WhisperVariant.fromString(variant)
                        ?: run {
                            _progressState.value = ExtractionProgress(
                                ModelType.WHISPER, variant,
                                DownloadState.Error("Unknown variant: $variant")
                            )
                            return
                        }
                    WhisperDownloader.downloadModel(
                        context = applicationContext,
                        variant = whisperVariant,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.value = ExtractionProgress(ModelType.WHISPER, variant, downloadState = state)
                            updateNotificationFromState(state)
                        }
                    )
                }
                ModelType.GEMMA -> {
                    val gemmaVariant = GemmaVariant.fromString(variant)
                    ModelDownloader.downloadModel(
                        context = applicationContext,
                        variant = gemmaVariant,
                        tokenManager = AppContainer.huggingFaceTokenManager,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.value = ExtractionProgress(ModelType.GEMMA, variant, downloadState = state)
                            updateNotificationFromState(state)
                        }
                    )
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled")
            _progressState.value = ExtractionProgress(
                modelType, variant,
                DownloadState.Cancelled("User cancelled")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during download", e)
            _progressState.value = ExtractionProgress(
                modelType, variant,
                DownloadState.Error(e.message ?: "Unknown error", e)
            )
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleCancel() {
        Log.i(TAG, "Cancel requested")
        isCancelling = true
        currentJob?.cancel()
        ParakeetDownloader.cancel()
        WhisperDownloader.cancel()
        ModelDownloader.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_extraction),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_extraction_description)
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
        subText: String? = null
    ): Notification {
        val cancelIntent = Intent(this, ExtractionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = android.app.PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(modelDisplayName)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
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
            .apply { subText?.let { setSubText(it) } }
            .build()
    }

    private fun updateNotificationFromState(state: DownloadState) {
        // Don't update notification after cancel has been requested — prevents
        // stale state (e.g. Retrying) from re-posting after stopForeground().
        if (isCancelling) return

        val notificationManager = getSystemService(NotificationManager::class.java)

        when (state) {
            is DownloadState.Connecting -> {
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(getString(R.string.download_status_connecting), indeterminate = true))
            }
            is DownloadState.CheckingAccess -> {
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(getString(R.string.download_status_checking_access), indeterminate = true))
            }
            is DownloadState.Downloading -> {
                val percent = state.progressPercent.toInt()
                val text = getString(R.string.notification_downloading_progress, percent)
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(text, progress = percent, maxProgress = 100))
            }
            is DownloadState.Retrying -> {
                val text = getString(R.string.download_status_retrying, state.attempt, state.maxRetries)
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(text, indeterminate = true))
            }
            is DownloadState.Extracting -> {
                val text = if (state.totalFiles > 0) {
                    getString(R.string.notification_extracting_progress, state.fileIndex, state.totalFiles)
                } else {
                    getString(R.string.download_status_extracting_files)
                }
                val maxProgress = if (state.totalFiles > 0) state.totalFiles else 0
                val progress = if (state.totalFiles > 0) state.fileIndex else 0
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(text, progress = progress, maxProgress = maxProgress,
                        indeterminate = state.totalFiles <= 0,
                        subText = getString(R.string.notification_extracting_hint)))
            }
            is DownloadState.Complete -> {
                val text = getString(R.string.notification_download_complete)
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(text, progress = 100, maxProgress = 100))
            }
            is DownloadState.Error -> {
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification("Error: ${state.message}"))
            }
            is DownloadState.Cancelled -> {
                notificationManager.notify(NOTIFICATION_ID,
                    createNotification(getString(R.string.action_cancel)))
            }
            else -> {}
        }
    }

    // ---- Variant helpers ----

    /** Resolves a string variant name to a Whisper [WhisperModelManager.Variant]. */
    private object WhisperVariant {
        fun fromString(name: String?): com.antivocale.app.transcription.WhisperModelManager.Variant? {
            return when (name) {
                "small" -> com.antivocale.app.transcription.WhisperModelManager.Variant.SMALL
                "turbo" -> com.antivocale.app.transcription.WhisperModelManager.Variant.TURBO
                "medium" -> com.antivocale.app.transcription.WhisperModelManager.Variant.MEDIUM
                "distil_large_v3" -> com.antivocale.app.transcription.WhisperModelManager.Variant.DISTIL_LARGE_V3
                else -> null
            }
        }
    }

    /** Resolves a string variant name to a Gemma [ModelDownloader.ModelVariant]. */
    private object GemmaVariant {
        fun fromString(name: String?): ModelDownloader.ModelVariant {
            return when (name) {
                "gemma_3n_e4b" -> ModelDownloader.ModelVariant.GEMMA_3N_E4B
                else -> ModelDownloader.ModelVariant.GEMMA_3N_E2B
            }
        }
    }
}
