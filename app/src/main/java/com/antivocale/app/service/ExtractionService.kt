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
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.di.AppContainer
import com.antivocale.app.transcription.ParakeetDownloader
import com.antivocale.app.transcription.Qwen3AsrDownloader
import com.antivocale.app.transcription.WhisperDownloader
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that wraps model download + extraction so it survives
 * screen-off and background interruption.
 *
 * Supports concurrent downloads — each variant runs in its own coroutine job.
 * The ViewModel observes [progressState] to update per-variant UI state.
 *
 * Notification follows the same pattern as [InferenceService].
 */
class ExtractionService : Service() {

    companion object {
        const val TAG = "ExtractionService"
        const val CHANNEL_ID = "extraction_channel"
        private const val NOTIFICATION_ID_BASE = 2001
        private const val NOTIFICATION_ID_RANGE = 100

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_EXTRACTION"

        const val EXTRA_MODEL_TYPE = "model_type"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_CANCEL_VARIANT = "cancel_variant"

        /**
         * Shared progress state — ViewModel collects this.
         *
         * Uses [MutableSharedFlow] (not StateFlow) so that concurrent progress
         * emissions from parallel downloads are not lost due to conflation.
         */
        private val _progressState = MutableSharedFlow<ExtractionProgress>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val progressState = _progressState.asSharedFlow()
    }

    /** Typed model type identifier — replaces raw strings across Service/ViewModel boundary. */
    enum class ModelType(val key: String) {
        PARAKEET("parakeet"),
        WHISPER("whisper"),
        QWEN3_ASR("qwen3-asr"),
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

    /** Active download jobs, keyed by "$modelType:$variant". */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Keys of downloads whose user-requested cancel is in progress. */
    private val cancellingKeys = mutableSetOf<String>()

    /** Per-download display names for notifications. */
    private val displayNames = ConcurrentHashMap<String, String>()

    /** Stable notification ID per download key. */
    private fun notificationIdForKey(key: String): Int =
        NOTIFICATION_ID_BASE + (key.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_RANGE

    /** Builds a unique key for tracking a download job. */
    private fun jobKey(modelType: ModelType, variant: String?): String =
        "${modelType.key}:${variant ?: ""}"

    /** Resolves a human-readable model name for the notification. */
    private fun resolveDisplayName(modelType: ModelType, variant: String?): String {
        return when (modelType) {
            ModelType.PARAKEET -> getString(R.string.parakeet_title)
            ModelType.WHISPER -> {
                val wv = WhisperVariant.fromString(variant)
                wv?.let { getString(it.titleResId) } ?: "Whisper"
            }
            ModelType.QWEN3_ASR -> {
                val qv = Qwen3Variant.fromString(variant)
                qv?.let { getString(it.titleResId) } ?: "Qwen3-ASR"
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
            handleCancel(intent)
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
        val key = jobKey(modelType, variant)
        val displayName = resolveDisplayName(modelType, variant)
        displayNames[key] = displayName
        val nid = notificationIdForKey(key)

        startForeground(nid, createNotification(
            getString(R.string.download_status_connecting),
            title = displayName,
            notificationId = nid,
            indeterminate = true
        ))

        // Only restart if the exact same download is already running
        activeJobs[key]?.cancel()

        val job = serviceScope.launch {
            executeDownload(modelType, variant)
            activeJobs.remove(key)
        }
        activeJobs[key] = job

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        activeJobs.keys.toList().forEach { key ->
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        displayNames.clear()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    private suspend fun executeDownload(modelType: ModelType, variant: String?) {
        val key = jobKey(modelType, variant)
        val nid = notificationIdForKey(key)
        try {
            when (modelType) {
                ModelType.PARAKEET -> {
                    ParakeetDownloader.downloadModel(
                        context = applicationContext,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.tryEmit(ExtractionProgress(ModelType.PARAKEET, downloadState = state))
                            updateNotificationFromState(key, state)
                        }
                    )
                }
                ModelType.WHISPER -> {
                    val whisperVariant = WhisperVariant.fromString(variant)
                        ?: run {
                            _progressState.tryEmit(ExtractionProgress(
                                ModelType.WHISPER, variant,
                                DownloadState.Error("Unknown variant: $variant")
                            ))
                            return
                        }
                    WhisperDownloader.downloadModel(
                        context = applicationContext,
                        variant = whisperVariant,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.tryEmit(ExtractionProgress(ModelType.WHISPER, variant, downloadState = state))
                            updateNotificationFromState(key, state)
                        }
                    )
                }
                ModelType.QWEN3_ASR -> {
                    val qwen3Variant = Qwen3Variant.fromString(variant)
                        ?: run {
                            _progressState.tryEmit(ExtractionProgress(
                                ModelType.QWEN3_ASR, variant,
                                DownloadState.Error("Unknown variant: $variant")
                            ))
                            return
                        }
                    Qwen3AsrDownloader.downloadModel(
                        context = applicationContext,
                        variant = qwen3Variant,
                        onProgress = {},
                        onStateChange = { state ->
                            _progressState.tryEmit(ExtractionProgress(ModelType.QWEN3_ASR, variant, downloadState = state))
                            updateNotificationFromState(key, state)
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
                            _progressState.tryEmit(ExtractionProgress(ModelType.GEMMA, variant, downloadState = state))
                            updateNotificationFromState(key, state)
                        }
                    )
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled: $key")
            _progressState.tryEmit(ExtractionProgress(
                modelType, variant,
                DownloadState.Cancelled("User cancelled")
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during download: $key", e)
            _progressState.tryEmit(ExtractionProgress(
                modelType, variant,
                DownloadState.Error(e.message ?: "Unknown error", e)
            ))
        } finally {
            activeJobs.remove(key)
            cancellingKeys.remove(key)
            displayNames.remove(key)
            if (activeJobs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancelAll()
                stopSelf()
            }
        }
    }

    /** Cancels a single job by key, tracking it in [cancellingKeys] to suppress notifications. */
    private fun cancelJobByKey(key: String) {
        cancellingKeys.add(key)
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(notificationIdForKey(key))
        displayNames.remove(key)
        // Note: cancellingKeys is NOT removed here — it stays active until the
        // coroutine's finally block runs, preventing stale state updates (e.g.
        // "Retrying") from being posted after cancellation.
    }

    /** Cancels the underlying downloader for a given model type and optional variant. */
    private fun cancelDownloaderFor(type: ModelType, variant: String? = null) {
        when (type) {
            ModelType.PARAKEET -> ParakeetDownloader.cancel()
            ModelType.WHISPER -> {
                if (variant != null) {
                    WhisperVariant.fromString(variant)?.let { WhisperDownloader.cancel(it) }
                } else {
                    WhisperDownloader.cancel()
                }
            }
            ModelType.QWEN3_ASR -> {
                if (variant != null) {
                    Qwen3Variant.fromString(variant)?.let { Qwen3AsrDownloader.cancel(it) }
                } else {
                    Qwen3AsrDownloader.cancel()
                }
            }
            ModelType.GEMMA -> {
                if (variant != null) {
                    ModelDownloader.cancel(GemmaVariant.fromString(variant))
                } else {
                    ModelDownloader.cancel()
                }
            }
        }
    }

    private fun handleCancel(intent: Intent) {
        val cancelVariant = intent.getStringExtra(EXTRA_CANCEL_VARIANT)
        val cancelModelType = intent.getStringExtra(EXTRA_MODEL_TYPE)

        if (cancelVariant != null && cancelModelType != null) {
            val type = ModelType.fromKey(cancelModelType) ?: return
            val key = jobKey(type, cancelVariant)
            Log.i(TAG, "Cancel requested for: $key")
            cancelJobByKey(key)
            cancelDownloaderFor(type, cancelVariant)
        } else if (cancelModelType != null) {
            val type = ModelType.fromKey(cancelModelType) ?: return
            Log.i(TAG, "Cancel all for model type: $type")
            val prefix = "${type.key}:"
            activeJobs.keys.filter { it.startsWith(prefix) }.toList().forEach { cancelJobByKey(it) }
            cancelDownloaderFor(type)
        } else {
            Log.i(TAG, "Cancel all requested")
            cancellingKeys.addAll(activeJobs.keys)
            activeJobs.keys.toList().forEach { cancelJobByKey(it) }
            activeJobs.clear()
            // Don't clear cancellingKeys — each coroutine's finally block removes its key,
            // preventing stale notification updates during cancellation.
            ModelType.entries.forEach { cancelDownloaderFor(it) }
        }

        if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        title: String,
        notificationId: Int,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        ongoing: Boolean = true,
        subText: String? = null
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setSilent(true)
            .setProgress(maxProgress, progress, indeterminate)
            .apply { subText?.let { setSubText(it) } }

        // Only show cancel action when there's a single active download
        // and the notification is still ongoing.
        if (ongoing && activeJobs.size <= 1) {
            val cancelIntent = Intent(this, ExtractionService::class.java).apply {
                action = ACTION_CANCEL
            }
            val cancelPendingIntent = android.app.PendingIntent.getService(
                this,
                notificationId,
                cancelIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                cancelPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotificationFromState(key: String, state: DownloadState) {
        // Don't update notification for a download that is being cancelled
        if (key in cancellingKeys) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val nid = notificationIdForKey(key)
        val title = displayNames[key] ?: ""

        when (state) {
            is DownloadState.Connecting -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.download_status_connecting), title, nid, indeterminate = true))
            }
            is DownloadState.CheckingAccess -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.download_status_checking_access), title, nid, indeterminate = true))
            }
            is DownloadState.Downloading -> {
                val percent = state.progressPercent.toInt()
                val text = getString(R.string.notification_downloading_progress, percent)
                notificationManager.notify(nid,
                    createNotification(text, title, nid, progress = percent, maxProgress = 100))
            }
            is DownloadState.Retrying -> {
                val text = getString(R.string.download_status_retrying, state.attempt, state.maxRetries)
                notificationManager.notify(nid,
                    createNotification(text, title, nid, indeterminate = true))
            }
            is DownloadState.Extracting -> {
                val text = if (state.totalFiles > 0) {
                    getString(R.string.notification_extracting_progress, state.fileIndex, state.totalFiles)
                } else {
                    getString(R.string.download_status_extracting_files)
                }
                val maxProgress = if (state.totalFiles > 0) state.totalFiles else 0
                val progress = if (state.totalFiles > 0) state.fileIndex else 0
                notificationManager.notify(nid,
                    createNotification(text, title, nid, progress = progress, maxProgress = maxProgress,
                        indeterminate = state.totalFiles <= 0,
                        subText = getString(R.string.notification_extracting_hint)))
            }
            is DownloadState.Complete -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.notification_download_complete), title, nid,
                        progress = 100, maxProgress = 100, ongoing = false))
            }
            is DownloadState.Error -> {
                notificationManager.notify(nid,
                    createNotification("Error: ${state.message}", title, nid, ongoing = false))
            }
            is DownloadState.Cancelled -> {
                notificationManager.cancel(nid)
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

    /** Resolves a string variant name to a Qwen3-ASR [Qwen3AsrModelManager.Variant]. */
    private object Qwen3Variant {
        fun fromString(name: String?): com.antivocale.app.transcription.Qwen3AsrModelManager.Variant? {
            return when (name) {
                "qwen3_asr_0_6b" -> com.antivocale.app.transcription.Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B
                else -> null
            }
        }
    }

    /** Resolves a string variant name to a Gemma [ModelDownloader.ModelVariant]. */
    private object GemmaVariant {
        fun fromString(name: String?): ModelDownloader.ModelVariant {
            return when (name) {
                "gemma_4_e2b" -> ModelDownloader.ModelVariant.GEMMA_4_E2B
                "gemma_4_e4b" -> ModelDownloader.ModelVariant.GEMMA_4_E4B
                "gemma_3n_e2b" -> ModelDownloader.ModelVariant.GEMMA_3N_E2B
                "gemma_3n_e4b" -> ModelDownloader.ModelVariant.GEMMA_3N_E4B
                else -> ModelDownloader.ModelVariant.GEMMA_4_E2B
            }
        }
    }
}
