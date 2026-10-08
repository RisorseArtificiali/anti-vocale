package com.antivocale.app.service

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.DualRefinementPolicy
import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.ClipboardWriter
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
 * contained duplication. Error and no-model notifications delegate to the
 * factory's builders (TASK-625/328); the completed-run posting delegates to
 * ResultNotificationFactory.postResult (TASK-757).
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

    override fun onPreviewResult(chunkText: String) {
        // No-op: like the interim results above, the fallback worker does
        // not surface preview text on its own notification; the interim row
        // (updateInterimResult) is this path's preview surface.
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
        refinementOutcome: String?,
        repetitionSuspected: Boolean
    ) {
        // The worker has no Tasker reply channel; only the service sends ACTION_TASKER_REPLY.
        // For share requests, mirror the service: auto-copy (if enabled) + post the result.
        if (isShareRequest) {
            coroutineScope.launch {
                // TASK-598 review F3: same derivation as the notification Copy action.
                val annotatedText = SubtitleFormatter.annotatedOrStored(resultText, segments)
                val copied = autoCopyIfEnabled(annotatedText, sourcePackage)
                val saveResult = saveTranscriptToFileIfEnabled(resultText, sourcePackage, segments, failedChunkCount)
                // TASK-758 review: this route used to drop the clipboard
                // note and the refinement verdicts the service route shows
                // (the worker twin of the same facts).
                resultNotificationFactory.postResult(
                    preferencesManager, perAppPreferencesManager,
                    annotatedText, segments, saveResult,
                    sourcePackage, taskId, confidence, detectedLanguage,
                    isPartial, failedChunkCount,
                    copiedToClipboard = copied,
                    streamedWithoutVad = streamedWithoutVad,
                    refinedFrom = refinementOutcome?.takeIf { it != DualRefinementPolicy.NOT_REFINED },
                    notRefined = refinementOutcome == DualRefinementPolicy.NOT_REFINED,
                    repetitionSuspected = repetitionSuspected,
                    originTag = "TranscriptionNotificationListener",
                )
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
        isMemoryFailure: Boolean,
        isDecodeError: Boolean
    ) {
        if (!isShareRequest) return
        if (isNoModelError) showNoModelNotification() else showErrorNotification(errorMessage, isMemoryFailure)
    }

    // ---- Auto-Copy (ported from InferenceService to keep the service untouched) ----

    private suspend fun autoCopyIfEnabled(transcriptionText: String, sourcePackage: String?): Boolean {
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
            // TASK-647: the clipboard is an exit surface on this route too
            // (code review F2: this twin of InferenceService.autoCopyIfEnabled
            // must stay in sync with it, signature included).
            val sig = TranscriptSignature.effectiveSpec(
                preferencesManager, appContext.getString(R.string.signature_default_text))
            // TASK-688: the write itself is the shared ClipboardWriter; the
            // signature above stays here (its single owner).
            ClipboardWriter.copy(
                appContext,
                appContext.getString(R.string.clipboard_label_transcription),
                TranscriptSignature.apply(transcriptionText, sig.text, sig.position)
            )
            Log.i(TAG, "Auto-copied transcription (${transcriptionText.length} chars), source=$sourcePackage, global=$globalAutoCopy, perApp=$perAppAutoCopy")
            Handler(Looper.getMainLooper()).post {
                com.antivocale.app.util.ToastCompat.show(appContext, R.string.copied_to_clipboard)
            }
            return true
        }
        return false
    }

    // ---- Auto-save to folder (issue #14) ----
    // Mirrors InferenceService.saveTranscriptToFileIfEnabled: keep the two paths
    // in sync (the format resolution itself lives in TranscriptFileSaver.saveAuto,
    // the single owner of the export fail-safe).

    /** @return the auto-save [TranscriptFileSaver.SaveResult] for the result
     *  notification (the InferenceService twin's contract). */
    private suspend fun saveTranscriptToFileIfEnabled(
        text: String,
        sourcePackage: String?,
        segments: List<TimedSegment>,
        failedChunkCount: Int
    ): TranscriptFileSaver.SaveResult {
        return withContext(Dispatchers.IO) {
            // ONE resolution, same torn-read guard as the service twin.
            val sig = TranscriptSignature.effectiveSpec(
                preferencesManager, appContext.getString(R.string.signature_default_text))
            TranscriptFileSaver.saveAuto(
                appContext,
                preferencesManager.outputFolderUri.first(),
                preferencesManager.transcriptExportFormat.first(),
                text, segments, failedChunkCount, sourcePackage,
                signature = sig.text,
                signaturePosition = sig.position,
            )
        }
    }

    // ---- Notifications (ported from InferenceService) ----

    private fun showErrorNotification(errorMessage: String, isMemoryFailure: Boolean) {
        // TASK-625: composition lives in ResultNotificationFactory (both error
        // surfaces and the TEST_SPI simulate op share this one builder).
        val notification = resultNotificationFactory.errorNotification(errorMessage, isMemoryFailure)
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed error notification: $errorMessage (memoryAction=$isMemoryFailure, id=$id)")
    }

    private fun showNoModelNotification() {
        // TASK-328: composition lives in ResultNotificationFactory.
        val notification = resultNotificationFactory.noModelNotification()
        val id = ResultNotificationFactory.nextNotificationId()
        notificationManager.notify(id, notification)
        Log.i(TAG, "Worker showed no-model notification (id=$id)")
    }


    companion object {
        private const val TAG = "TranscriptionNotificationListener"

    }
}
