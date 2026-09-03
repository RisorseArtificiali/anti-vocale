package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationManager
import com.antivocale.app.util.AppNotificationChannel
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.ModelDownloader
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.CatalogVariantUi
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.SherpaModelDownloader
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject

/**
 * Foreground service that wraps model download + extraction so it survives
 * screen-off and background interruption.
 *
 * Supports concurrent downloads — each variant runs in its own coroutine job.
 * The ViewModel observes [progressState] to update per-variant UI state.
 *
 * The download target is identified by a plain model KEY string: a bundled
 * catalog entry id for the built-in sherpa-onnx models, or
 * [LlmTranscriptionBackend.BACKEND_ID] for the Gemma model. There is no
 * per-model dispatch — built-in downloads all go through
 * [SherpaModelDownloader] and only the non-catalog Gemma path is separate.
 *
 * Notification follows the same pattern as [InferenceService].
 */
@AndroidEntryPoint
class ExtractionService : Service() {

    @Inject lateinit var huggingFaceTokenManager: HuggingFaceTokenManager

    companion object {
        const val TAG = "ExtractionService"
        val CHANNEL_ID = AppNotificationChannel.EXTRACTION.id
        // Internal so the reserved-range contract test (ResultNotificationFactoryTest,
        // TASK-329) can pin the band's derived top instead of a stale-able literal.
        internal const val NOTIFICATION_ID_BASE = 2001
        internal const val NOTIFICATION_ID_RANGE = 100

        const val ACTION_CANCEL = "com.antivocale.app.CANCEL_EXTRACTION"

        /** Model key: a bundled catalog entry id, or [LlmTranscriptionBackend.BACKEND_ID]. */
        const val EXTRA_MODEL_KEY = "model_key"
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

    data class ExtractionProgress(
        val modelKey: String,
        val variant: String? = null,
        val downloadState: DownloadState
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)

    /** Active download jobs, keyed by "$modelKey:$variant". */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Keys of downloads whose user-requested cancel is in progress. */
    private val cancellingKeys = mutableSetOf<String>()

    /** Per-download display names for notifications. */
    private val displayNames = ConcurrentHashMap<String, String>()

    /** Stable notification ID per download key. */
    private fun notificationIdForKey(key: String): Int =
        NOTIFICATION_ID_BASE + (key.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_RANGE

    /** Builds a unique key for tracking a download job. */
    private fun jobKey(modelKey: String, variant: String?): String =
        "$modelKey:${variant ?: ""}"

    /** Resolves a human-readable model name for the notification. */
    private fun resolveDisplayName(modelKey: String, variant: String?): String {
        if (BundledCatalog.byId(modelKey) != null) {
            val variantName = variant ?: BundledCatalog.byId(modelKey)!!.defaultVariant.name
            return getString(CatalogVariantUi.of(modelKey, variantName).titleResId)
        }
        if (modelKey == LlmTranscriptionBackend.BACKEND_ID) {
            return GemmaVariant.fromString(variant).displayName
        }
        return modelKey
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val modelKey = intent.getStringExtra(EXTRA_MODEL_KEY)
            val variant = intent.getStringExtra(EXTRA_CANCEL_VARIANT)
            val key = modelKey?.let { jobKey(it, variant) }
            val nid = key?.let { notificationIdForKey(it) } ?: NOTIFICATION_ID_BASE
            startForeground(nid, createNotification(
                getString(R.string.notification_cancelling),
                title = key?.let { displayNames[it] } ?: getString(R.string.app_name),
                notificationId = nid,
                indeterminate = true
            ))
            handleCancel(intent)
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.w(TAG, "Null intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val modelKey = intent.getStringExtra(EXTRA_MODEL_KEY) ?: run {
            Log.w(TAG, "No model_key extra, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val variant = intent.getStringExtra(EXTRA_VARIANT)
        val key = jobKey(modelKey, variant)
        val displayName = resolveDisplayName(modelKey, variant)
        displayNames[key] = displayName
        val nid = notificationIdForKey(key)

        startForeground(nid, createNotification(
            getString(R.string.download_status_connecting),
            title = displayName,
            notificationId = nid,
            indeterminate = true,
            cancelModelKey = modelKey,
            cancelVariant = variant
        ))

        // Only restart if the exact same download is already running
        activeJobs[key]?.cancel()

        val job = serviceScope.launch {
            executeDownload(modelKey, variant)
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

    private suspend fun executeDownload(modelKey: String, variant: String?) {
        val key = jobKey(modelKey, variant)
        val nid = notificationIdForKey(key)
        try {
            val onStateChange: (DownloadState) -> Unit = { state ->
                _progressState.tryEmit(ExtractionProgress(modelKey, variant, downloadState = state))
                updateNotificationFromState(key, state)
            }
            if (BundledCatalog.byId(modelKey) != null) {
                SherpaModelDownloader.of(modelKey).downloadModel(
                    context = applicationContext,
                    variantName = variant,
                    onProgress = {},
                    onStateChange = onStateChange
                )
            } else if (modelKey == LlmTranscriptionBackend.BACKEND_ID) {
                val gemmaVariant = GemmaVariant.fromString(variant)
                ModelDownloader.downloadModel(
                    context = applicationContext,
                    variant = gemmaVariant,
                    tokenManager = huggingFaceTokenManager,
                    onProgress = {},
                    onStateChange = onStateChange
                )
            } else {
                _progressState.tryEmit(ExtractionProgress(
                    modelKey, variant,
                    DownloadState.Error("Unknown model key: $modelKey")
                ))
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled: $key")
            _progressState.tryEmit(ExtractionProgress(
                modelKey, variant,
                DownloadState.Cancelled("User cancelled")
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during download: $key", e)
            _progressState.tryEmit(ExtractionProgress(
                modelKey, variant,
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

    /** Cancels the underlying downloader for a given model key and optional variant. */
    private fun cancelDownloaderFor(modelKey: String, variant: String? = null) {
        if (BundledCatalog.byId(modelKey) != null) {
            SherpaModelDownloader.of(modelKey).cancel(variant)
        } else if (modelKey == LlmTranscriptionBackend.BACKEND_ID) {
            if (variant != null) {
                ModelDownloader.cancel(GemmaVariant.fromString(variant))
            } else {
                ModelDownloader.cancel()
            }
        }
        // Unknown/GGUF/external keys: no service-driven downloader.
    }

    private fun handleCancel(intent: Intent) {
        val cancelVariant = intent.getStringExtra(EXTRA_CANCEL_VARIANT)
        val cancelModelKey = intent.getStringExtra(EXTRA_MODEL_KEY)

        if (cancelVariant != null && cancelModelKey != null) {
            val key = jobKey(cancelModelKey, cancelVariant)
            Log.i(TAG, "Cancel requested for: $key")
            cancelJobByKey(key)
            cancelDownloaderFor(cancelModelKey, cancelVariant)
        } else if (cancelModelKey != null) {
            Log.i(TAG, "Cancel all for model key: $cancelModelKey")
            val prefix = "$cancelModelKey:"
            activeJobs.keys.filter { it.startsWith(prefix) }.toList().forEach { cancelJobByKey(it) }
            cancelDownloaderFor(cancelModelKey)
        } else {
            Log.i(TAG, "Cancel all requested")
            cancellingKeys.addAll(activeJobs.keys)
            activeJobs.keys.toList().forEach { cancelJobByKey(it) }
            activeJobs.clear()
            // Don't clear cancellingKeys — each coroutine's finally block removes its key,
            // preventing stale notification updates during cancellation.
            (BuiltInBackendIds.ALL + LlmTranscriptionBackend.BACKEND_ID).forEach { cancelDownloaderFor(it) }
        }

        if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        AppNotificationChannel.EXTRACTION.create(this)
    }

    private fun createNotification(
        contentText: String,
        title: String,
        notificationId: Int,
        progress: Int = 0,
        maxProgress: Int = 0,
        indeterminate: Boolean = false,
        ongoing: Boolean = true,
        subText: String? = null,
        cancelModelKey: String? = null,
        cancelVariant: String? = null,
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

        // TASK-385: per-key cancel on EVERY ongoing notification. The old
        // single-download-only guard left multi-download users with no way to
        // cancel anything from the shade; the intent already carries the key
        // via EXTRA_MODEL_KEY/EXTRA_CANCEL_VARIANT (same extras onStartCommand
        // reads), so each notification cancels exactly its own job.
        if (ongoing) {
            val cancelIntent = Intent(this, ExtractionService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_KEY, cancelModelKey)
                putExtra(EXTRA_CANCEL_VARIANT, cancelVariant)
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
        // TASK-385: split the composite "model:variant" key back into the pair
        // the per-notification cancel action needs.
        val keyParts = key.split(':', limit = 2)
        val cancelModelKey = keyParts[0]
        val cancelVariant = keyParts.getOrNull(1)?.takeIf { it.isNotEmpty() }

        when (state) {
            is DownloadState.Connecting -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.download_status_connecting), title, nid, indeterminate = true, cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.CheckingAccess -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.download_status_checking_access), title, nid, indeterminate = true, cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.Downloading -> {
                val percent = state.progressPercent.toInt()
                val text = getString(R.string.notification_downloading_progress, percent)
                notificationManager.notify(nid,
                    createNotification(text, title, nid, progress = percent, maxProgress = 100, cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.Retrying -> {
                val text = getString(R.string.download_status_retrying, state.attempt, state.maxRetries)
                notificationManager.notify(nid,
                    createNotification(text, title, nid, indeterminate = true, cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
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
                        subText = getString(R.string.notification_extracting_hint),
                        cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.Complete -> {
                notificationManager.notify(nid,
                    createNotification(getString(R.string.notification_download_complete), title, nid,
                        progress = 100, maxProgress = 100, ongoing = false,
                        cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.Error -> {
                notificationManager.notify(nid,
                    createNotification("Error: ${state.message}", title, nid, ongoing = false, cancelModelKey = cancelModelKey, cancelVariant = cancelVariant))
            }
            is DownloadState.Cancelled -> {
                notificationManager.cancel(nid)
            }
            else -> {}
        }
    }

    // ---- Variant helpers ----

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