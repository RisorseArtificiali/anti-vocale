package com.antivocale.app.work

import com.antivocale.app.data.local.LogDao
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
import com.antivocale.app.receiver.SubtitleChoice
import com.antivocale.app.service.TranscriptionNotificationListener
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.AppNotificationChannel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.Dispatchers

/**
 * Expedited one-shot [CoroutineWorker] that fires when the user ignores the subtitle-choice
 * notification for the user-configured timeout (PreferencesManager.subtitleChoiceTimeoutMinutes). It runs
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
    private val perAppPreferencesManager: PerAppPreferencesManager,
    /** TASK-820: feeds the listener's save-outcome persistence. */
    private val logDao: LogDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH)
        val taskId = inputData.getString(KEY_TASK_ID) ?: "subtitle_timeout_${System.currentTimeMillis()}"
        val sourcePackage = inputData.getString(KEY_SOURCE_PACKAGE)
        val backendOverride = inputData.getString(KEY_BACKEND_OVERRIDE)
        // F5: the offerer's source rides the work data; data persisted by a
        // pre-source build falls back to share (its only origin then).
        val source = inputData.getString(KEY_SOURCE)
            ?: com.antivocale.app.service.InferenceService.SOURCE_SHARE
        // TASK-842: the intake content hash, onto the fallback's row.
        val contentHash = inputData.getString(KEY_CONTENT_HASH)

        if (filePath.isNullOrBlank()) {
            Log.e(TAG, "Missing file path input — cannot run ASR fallback")
            return Result.failure()
        }

        Log.i(TAG, "Subtitle choice timed out for taskId=$taskId — running ASR fallback")

        // Cancel the choice notification so the user does not see a stale prompt after ASR
        // has already produced a result notification. The legacy raw-hash id too:
        // a prompt posted by a pre-TASK-440 build survives an in-window app update
        // (this worker does), and a stale prompt with live actions is worse than none.
        try {
            SubtitleChoice.cancelPrompt(applicationContext, filePath, taskId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel choice notification", e)
        }

        // Promote to a foreground worker so the long-running ASR is not killed (Android 12+).
        // The foreground notification doubles as the "we're transcribing" status.
        runCatching { setForeground(buildForegroundInfo()) }

        // The listener posts the result/error notification exactly as InferenceService would.
        // Its coroutineScope is this worker's; since TASK-820 it also runs the
        // auto-save (a SAF file write) and the save-outcome persistence, so
        // doWork JOINS the scope before returning (below) instead of relying
        // on side effects completing inside onSuccess.
        val workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
        val listener = TranscriptionNotificationListener(
            appContext = applicationContext,
            preferencesManager = preferencesManager,
            perAppPreferencesManager = perAppPreferencesManager,
            coroutineScope = workerScope,
            logDao = logDao,
        )

        // TASK-526: the worker bypasses InferenceService's enqueue layer,
        // so the QUEUED row the service creates never exists here;
        // markProcessing and logError inside processRequest are no-ops
        // without it (getByTaskId() ?: return), and the failure left
        // nothing in History while the notification fired. Insert the row
        // ourselves before the request starts.
        orchestrator.logQueued(
            taskId = taskId,
            requestType = com.antivocale.app.receiver.TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            filePath = filePath,
            sourcePackageName = sourcePackage,
            contentHash = contentHash
        )
        return try {
            val cacheDir = applicationContext.cacheDir
            val result = orchestrator.processRequest(
                taskId = taskId,
                requestType = com.antivocale.app.receiver.TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
                prompt = "",
                filePath = filePath,
                source = source,
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
            // TASK-820 review: await the listener's side-effect jobs (the save
            // and the outcome persistence) first; WorkManager may drop the
            // foreground promotion the moment this returns and kill the
            // process with the write still in flight.
            workerScope.coroutineContext[Job]?.children?.forEach { it.join() }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ASR fallback threw for taskId=$taskId", e)
            // Result.failure would trigger retry policy; there is none configured, so this
            // just marks the unique work done. The error notification is the user's signal.
            workerScope.coroutineContext[Job]?.children?.forEach { it.join() }
            Result.failure()
        }
    }

    /**
     * Builds the foreground-notification [ForegroundInfo] shown while the ASR fallback runs.
     * Uses the existing INFERENCE channel (IMPORTANCE_LOW, no badge) to match the service's
     * in-progress notification.
     *
     * The explicit DATA_SYNC type is mandatory (Crashlytics 2026-10-11, 1.13.2): WorkManager
     * 2.10 passes the ForegroundInfo's type straight to Service.startForeground, and a type-0
     * (NONE) call is prohibited for apps targeting 34+, killing the process inside
     * SystemForegroundService.onStartCommand where nothing in this worker can catch it. The
     * type must be a subset of the manifest override on SystemForegroundService (dataSync,
     * declared in AndroidManifest.xml) and needs FOREGROUND_SERVICE_DATA_SYNC (both present).
     * The type constant exists since API 29; below that the untyped 2-arg form is the only API.
     */
    private fun buildForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext)

    companion object {
        private const val TAG = "SubtitleChoiceTimeoutWorker"

        // Reserved-range contract (TASK-329): fixed ids must stay below
        // ResultNotificationFactory.RESULT_NOTIFICATION_ID_BASE. Internal so
        // the contract test can pin this value.
        internal const val NOTIFICATION_ID = 1003

        /** The FGS promotion's contract: a typed ForegroundInfo matching the
         *  manifest's SystemForegroundService dataSync override (internal so
         *  the pin test can reach it without a WorkerParameters fixture). */
        internal fun foregroundInfo(context: Context): ForegroundInfo {
            AppNotificationChannel.INFERENCE.create(context)
            val notification = NotificationCompat.Builder(context, AppNotificationChannel.INFERENCE.id)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.processing_audio))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        const val KEY_FILE_PATH = "file_path"
        const val KEY_TASK_ID = "task_id"
        const val KEY_SOURCE_PACKAGE = "source_package"
        const val KEY_SOURCE = "source"
        /** TASK-842: the intake content hash, re-emitted on the fallback. */
        const val KEY_CONTENT_HASH = "content_hash"
        const val KEY_BACKEND_OVERRIDE = "backend_override"
    }
}
