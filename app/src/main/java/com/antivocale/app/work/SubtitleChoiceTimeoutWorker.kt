package com.antivocale.app.work

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.receiver.ShareReceiverActivity
import com.antivocale.app.service.TranscriptionNotificationListener
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.AppNotificationChannel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.Dispatchers

/**
 * Expedited one-shot [CoroutineWorker] that fires when the user ignores the subtitle-choice
 * notification for [ShareReceiverActivity.SUBTITLE_CHOICE_TIMEOUT_MINUTES] minutes. It runs
 * the normal ASR path directly through [TranscriptionOrchestrator] (it does NOT call
 * `startForegroundService(InferenceService)`, which is blocked from the background on
 * Android 12+) and posts the result via [TranscriptionNotificationListener].
 *
 * Either notification tap ([NotificationActionReceiver.ACTION_USE_SUBTITLES] /
 * [ACTION_TRANSCRIBE_AUDIO]) cancels this worker's unique work, so it only fires when the
 * user genuinely does nothing.
 *
 * Wired for DI via [HiltWorker] + [dagger.hilt.android.HiltWorkerFactory] (the factory is
 * provided to WorkManager by [com.antivocale.app.BridgeApplication]'s
 * `Configuration.Provider`).
 */
@HiltWorker
class SubtitleChoiceTimeoutWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: TranscriptionOrchestrator,
    private val preferencesManager: PreferencesManager,
    private val perAppPreferencesManager: PerAppPreferencesManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH)
        val taskId = inputData.getString(KEY_TASK_ID) ?: "subtitle_timeout_${System.currentTimeMillis()}"
        val sourcePackage = inputData.getString(KEY_SOURCE_PACKAGE)
        val backendOverride = inputData.getString(KEY_BACKEND_OVERRIDE)

        if (filePath.isNullOrBlank()) {
            Log.e(TAG, "Missing file path input — cannot run ASR fallback")
            return Result.failure()
        }

        Log.i(TAG, "Subtitle choice timed out for taskId=$taskId — running ASR fallback")

        // Cancel the choice notification so the user does not see a stale prompt after ASR
        // has already produced a result notification.
        try {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(ShareReceiverActivity.choiceNotificationId(taskId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel choice notification", e)
        }

        // Promote to a foreground worker so the long-running ASR is not killed (Android 12+).
        // The foreground notification doubles as the "we're transcribing" status.
        runCatching { setForeground(buildForegroundInfo()) }

        // The listener posts the result/error notification exactly as InferenceService would.
        // Its coroutineScope is this worker's — it dies when doWork returns, which is fine
        // because the only async side effect (auto-copy) is awaited inside onSuccess.
        val workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
        val listener = TranscriptionNotificationListener(
            appContext = applicationContext,
            preferencesManager = preferencesManager,
            perAppPreferencesManager = perAppPreferencesManager,
            coroutineScope = workerScope
        )

        return try {
            val cacheDir = applicationContext.cacheDir
            val result = orchestrator.processRequest(
                taskId = taskId,
                requestType = "audio",
                prompt = "",
                filePath = filePath,
                source = "share",
                sourcePackage = sourcePackage,
                backendOverride = backendOverride,
                trackIndex = -1,
                queuePosition = 1,
                queueTotal = 1,
                context = applicationContext,
                cacheDir = cacheDir,
                listener = listener,
                coroutineScope = workerScope
            )
            if (result.isSuccess) {
                Log.i(TAG, "ASR fallback succeeded for taskId=$taskId")
            } else {
                Log.w(TAG, "ASR fallback reported failure for taskId=$taskId: ${result.exceptionOrNull()?.message}")
            }
            // The orchestrator already reported success/error to the listener (which posts
            // the notification). Surface success to WorkManager regardless so the job clears.
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ASR fallback threw for taskId=$taskId", e)
            // Result.failure would trigger retry policy; there is none configured, so this
            // just marks the unique work done. The error notification is the user's signal.
            Result.failure()
        }
    }

    /**
     * Builds the foreground-notification [ForegroundInfo] shown while the ASR fallback runs.
     * Uses the existing INFERENCE channel (IMPORTANCE_LOW, no badge) to match the service's
     * in-progress notification.
     */
    private fun buildForegroundInfo(): ForegroundInfo {
        AppNotificationChannel.INFERENCE.create(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, AppNotificationChannel.INFERENCE.id)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.processing_audio))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "SubtitleChoiceTimeoutWorker"
        private const val NOTIFICATION_ID = 1003

        const val KEY_FILE_PATH = "file_path"
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_PACKAGE = "source_package"
        const val KEY_BACKEND_OVERRIDE = "backend_override"
    }
}
