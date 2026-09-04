package com.antivocale.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.TranscriptFileSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [TranscriptionListener] that posts the result/error notifications the same way
 * [InferenceService] does, but without being tied to an Android [android.app.Service].
 *
 * Used by [com.antivocale.app.work.SubtitleChoiceTimeoutWorker] (the 5-minute ASR fallback)
 * because a WorkManager Worker cannot call `startForegroundService(InferenceService)` from
 * the background on Android 12+. Instead the Worker runs the orchestrator directly and uses
 * this listener to surface the result to the user.
 *
 * **Design note:** Both this listener and [InferenceService] now delegate result
 * notification building to [ResultNotificationFactory], eliminating the earlier
 * contained duplication. This class retains its own error and no-model notification
 * builders (those are not yet delegated to the factory; near-copies still exist
 * in InferenceService and are out of scope for TASK-327).
 *
 * @param appContext Application context used for notificationManager / getString / packages.
 * @param preferencesManager For the global auto-copy preference fallback.
 * @param perAppPreferencesManager For per-source-app notification preferences.
 * @param coroutineScope Scope for the auto-copy side effect (mirrors the service's
 *        `serviceScope.launch` inside onSuccess). Owned by the caller (Worker).
 */
class TranscriptionNotificationListener(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
    private val perAppPreferencesManager: PerAppPreferencesManager,
    private val coroutineScope: CoroutineScope
) : TranscriptionListener {

    private val notificationManager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val resultNotificationFactory = ResultNotificationFactory(appContext)

    init {
        // Ensure the result channel exists (idempotent). The service also creates it in
        // onCreate; the Worker may run before the service was ever started.
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(appContext)
    }

    override fun onStatusUpdate(message: String) {
        // No-op for the worker case: the worker posts its own foreground "Transcribing audio…"
        // notification; transient status updates are not surfaced.
    }

    override fun onIndeterminateProgress(message: String) {
        // No-op: the worker's foreground notification is static.
    }

    override fun onProgress(
        contentText: String,
        progressPercent: Int,
        etaText: String,
        durationSeconds: Int,
        startTimeMillis: Long,
        queuedCount: Int
    ) {
        // No-op: the worker runs a single ASR request with its own foreground notification.
    }

    override fun onInterimResult(
        contentText: String,
        bigText: String,
        subText: String,
        chunkIndex: Int,
        chunkText: String?,
        totalChunks: Int
    ) {
        // No-op: interim progressive results are not surfaced by the fallback worker.
    }

    override fun onSuccess(
        taskId: String,
        resultText: String,
        isShareRequest: Boolean,
        sourcePackage: String?,
        durationMs: Long,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean,
        failedChunkCount: Int,
        streamedWithoutVad: Boolean
    ) {
        // The worker has no Tasker reply channel; only the service sends ACTION_TASKER_REPLY.
        // For share requests, mirror the service: auto-copy (if enabled) + post the result.
        if (isShareRequest) {
            coroutineScope.launch {
                autoCopyIfEnabled(resultText, sourcePackage)
                saveTranscriptToFileIfEnabled(resultText, sourcePackage)
                showResultNotification(resultText, sourcePackage, taskId, confidence, detectedLanguage, isPartial, failedChunkCount, streamedWithoutVad = streamedWithoutVad)
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
        if (!isShareRequest) return
        if (isNoModelError) showNoModelNotification() else showErrorNotification(errorMessage)
    }

    // ---- Auto-Copy (ported from InferenceService to keep the service untouched) ----

    private suspend fun autoCopyIfEnabled(transcriptionText: String, sourcePackage: String?) {
        // Effective auto-copy = global toggle OR per-app preference (issue #13). Mirrors
        // InferenceService.autoCopyIfEnabled — keep the two paths in sync.
        val globalAutoCopy = preferencesManager.autoCopyEnabled.first()
        val perAppAutoCopy = sourcePackage?.let { pkg ->
            try {
                perAppPreferencesManager.getCurrentPreferences(pkg).autoCopy
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $pkg", e)
                false
            }
        } ?: false

        if (globalAutoCopy || perAppAutoCopy) {
            val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                appContext.getString(R.string.clipboard_label_transcription),
                transcriptionText
            )
            clipboardManager.setPrimaryClip(clip)
            Log.i(TAG, "Auto-copied transcription (${transcriptionText.length} chars), source=$sourcePackage, global=$globalAutoCopy, perApp=$perAppAutoCopy")
            Handler(Looper.getMainLooper()).post {
                com.antivocale.app.util.ToastCompat.show(appContext, R.string.copied_to_clipboard)
            }
        }
    }

    // ---- Auto-save to folder (issue #14, mirrors InferenceService) ----

    private suspend fun saveTranscriptToFileIfEnabled(text: String, sourcePackage: String?) {
        val treeUriStr = preferencesManager.outputFolderUri.first() ?: return
        val treeUri = Uri.parse(treeUriStr)
        val name = withContext(Dispatchers.IO) {
            TranscriptFileSaver.save(appContext, treeUri, text, sourcePackage)
        }
        if (name != null) {
            Log.i(TAG, "Saved transcript to output folder: $name")
        }
    }

    // ---- Notifications (ported from InferenceService) ----

    private suspend fun showResultNotification(
        transcriptionText: String,
        sourcePackage: String?,
        taskId: String,
        confidence: Float?,
        detectedLanguage: String?,
        isPartial: Boolean = false,
        failedChunkCount: Int = 0,
        streamedWithoutVad: Boolean = false
    ) {
        val prefs = if (sourcePackage != null) {
            try {
                perAppPreferencesManager.getCurrentPreferences(sourcePackage)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get per-app preferences for $sourcePackage, using defaults", e)
                AppNotificationPreferences.default()
            }
        } else {
            AppNotificationPreferences.default()
        }

        val id = ResultNotificationFactory.nextNotificationId()
        val spec = ResultNotificationSpec(
            transcriptionText = transcriptionText,
            taskId = taskId,
            sourcePackage = sourcePackage,
            confidence = confidence,
            detectedLanguage = detectedLanguage,
            isPartial = isPartial,
            failedChunkCount = failedChunkCount,
            notificationId = id,
            streamedWithoutVad = streamedWithoutVad,
            firstPostedAt = System.currentTimeMillis()
        )
        val notification = resultNotificationFactory.build(spec, prefs)
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed result notification (${transcriptionText.length} chars) (id=$id)")
    }

    private fun showErrorNotification(errorMessage: String) {
        val notification = NotificationCompat.Builder(appContext, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(appContext.getString(R.string.transcription_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(buildLaunchPendingIntent())
            .setAutoCancel(true)
            .build()
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed error notification: $errorMessage (id=$id)")
    }

    private fun showNoModelNotification() {
        val openPendingIntent = buildLaunchPendingIntent(navigateToModelTab = true)
        val notification = NotificationCompat.Builder(appContext, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(appContext.getString(R.string.notification_no_model_title))
            .setContentText(appContext.getString(R.string.notification_no_model_message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_set_as,
                appContext.getString(R.string.notification_no_model_action),
                openPendingIntent
            )
            .build()
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed no-model notification (id=$id)")
    }

    private fun buildLaunchPendingIntent(
        navigateToModelTab: Boolean = false,
        highlightTaskId: String? = null
    ): PendingIntent {
        val requestCode = when {
            highlightTaskId != null -> highlightTaskId.hashCode()
            navigateToModelTab -> RC_LAUNCH_MODEL_TAB
            else -> RC_LAUNCH_DEFAULT
        }
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
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
        return PendingIntent.getActivity(
            appContext, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "TranscriptionNotificationListener"
        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1
    }
}
