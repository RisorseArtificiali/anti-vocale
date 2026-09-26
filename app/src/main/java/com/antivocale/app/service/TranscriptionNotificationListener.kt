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
import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.ui.SettingsFocusRow
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.SubtitleFormatter
import com.antivocale.app.util.TranscriptSignature
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
 * Used by [com.antivocale.app.work.SubtitleChoiceTimeoutWorker] (the timed ASR fallback (user-configured timeout))
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
        streamedWithoutVad: Boolean,
        segments: List<TimedSegment>,
        refinementOutcome: String?
    ) {
        // The worker has no Tasker reply channel; only the service sends ACTION_TASKER_REPLY.
        // For share requests, mirror the service: auto-copy (if enabled) + post the result.
        if (isShareRequest) {
            coroutineScope.launch {
                // TASK-598 review F3: same derivation as the notification Copy action.
                val annotatedText = SubtitleFormatter.annotatedOrStored(resultText, segments)
                autoCopyIfEnabled(annotatedText, sourcePackage)
                saveTranscriptToFileIfEnabled(resultText, sourcePackage, segments, failedChunkCount)
                showResultNotification(annotatedText, sourcePackage, taskId, confidence, detectedLanguage, isPartial, failedChunkCount, streamedWithoutVad = streamedWithoutVad, segments = segments)
            }
        }
    }

    override fun onError(
        taskId: String,
        errorCode: String,
        errorMessage: String,
        isShareRequest: Boolean,
        isNoModelError: Boolean,
        durationMs: Long,
        isMemoryFailure: Boolean
    ) {
        if (!isShareRequest) return
        if (isNoModelError) showNoModelNotification() else showErrorNotification(errorMessage, isMemoryFailure)
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
            // TASK-647: the clipboard is an exit surface on this route too
            // (code review F2: this twin of InferenceService.autoCopyIfEnabled
            // must stay in sync with it, signature included).
            val sig = TranscriptSignature.effectiveSpec(
                preferencesManager, appContext.getString(R.string.signature_default_text))
            val clip = ClipData.newPlainText(
                appContext.getString(R.string.clipboard_label_transcription),
                TranscriptSignature.apply(transcriptionText, sig.text, sig.position)
            )
            clipboardManager.setPrimaryClip(clip)
            Log.i(TAG, "Auto-copied transcription (${transcriptionText.length} chars), source=$sourcePackage, global=$globalAutoCopy, perApp=$perAppAutoCopy")
            Handler(Looper.getMainLooper()).post {
                com.antivocale.app.util.ToastCompat.show(appContext, R.string.copied_to_clipboard)
            }
        }
    }

    // ---- Auto-save to folder (issue #14) ----
    // Mirrors InferenceService.saveTranscriptToFileIfEnabled: keep the two paths
    // in sync (the format resolution itself lives in TranscriptFileSaver.saveAuto,
    // the single owner of the export fail-safe).

    private suspend fun saveTranscriptToFileIfEnabled(
        text: String,
        sourcePackage: String?,
        segments: List<TimedSegment>,
        failedChunkCount: Int
    ) {
        val name = withContext(Dispatchers.IO) {
            TranscriptFileSaver.saveAuto(
                appContext,
                preferencesManager.outputFolderUri.first(),
                preferencesManager.transcriptExportFormat.first(),
                text, segments, failedChunkCount, sourcePackage,
                signature = TranscriptSignature.effectiveSpec(
                    preferencesManager, appContext.getString(R.string.signature_default_text)).let { it.text },
                signaturePosition = TranscriptSignature.effectiveSpec(
                    preferencesManager, appContext.getString(R.string.signature_default_text)).position,
            
            )
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
        streamedWithoutVad: Boolean = false,
        segments: List<TimedSegment>,
    ) {
        // TASK-598 F5: mirrors InferenceService.showResultNotification (keep
        // the two paths in sync): the notification's whole text derives the
        // speaker-annotated form when the run carries labels; raw fallback.
        val text = SubtitleFormatter.annotatedOrStored(transcriptionText, segments)
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
            transcriptionText = text,
            signatureText = TranscriptSignature.effectiveSpec(
                preferencesManager, appContext.getString(R.string.signature_default_text)).let { it.text },
            signaturePosition = TranscriptSignature.effectiveSpec(
                preferencesManager, appContext.getString(R.string.signature_default_text)).position,
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
        Log.i(TAG, "Worker showed result notification (${text.length} chars) (id=$id)")
    }

    private fun showErrorNotification(errorMessage: String, isMemoryFailure: Boolean) {
        // TASK-625: composition lives in ResultNotificationFactory (both error
        // surfaces and the TEST_SPI simulate op share this one builder).
        val notification = resultNotificationFactory.errorNotification(errorMessage, isMemoryFailure)
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed error notification: $errorMessage (memoryAction=$isMemoryFailure, id=$id)")
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
        highlightTaskId: String? = null,
        navigateToSettingsRow: SettingsFocusRow? = null
    ): PendingIntent {
        val requestCode = when {
            highlightTaskId != null ->
                RC_HASH_BASE + highlightTaskId.hashCode().let { if (it < 0) it.inv() else it }
            navigateToSettingsRow != null -> RC_LAUNCH_SETTINGS_ROW
            navigateToModelTab -> RC_LAUNCH_MODEL_TAB
            else -> RC_LAUNCH_DEFAULT
        }
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            // In-app deep links (highlight, settings row) hand the extra to the
            // live activity (onNewIntent) instead of clearing its task.
            if (highlightTaskId != null || navigateToSettingsRow != null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            when {
                highlightTaskId != null ->
                    putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, highlightTaskId)
                navigateToSettingsRow != null ->
                    putExtra(MainActivity.EXTRA_NAVIGATE_TO_SETTINGS_ROW, navigateToSettingsRow.name)
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
        private const val RC_LAUNCH_SETTINGS_ROW = 2

        // TaskId-hash codes live above the small-constant band (see the same
        // comment on InferenceService.RC_HASH_BASE).
        private const val RC_HASH_BASE = 1000
    }
}
