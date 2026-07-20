package com.antivocale.app.service

import android.app.Notification
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
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.transcription.Language
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.TranscriptFileSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [TranscriptionListener] that posts the result/error notifications the same way
 * [InferenceService] does, but without being tied to an Android [android.app.Service].
 *
 * Used by [com.antivocale.app.work.SubtitleChoiceTimeoutWorker] (the 5-minute ASR fallback)
 * because a WorkManager Worker cannot call `startForegroundService(InferenceService)` from
 * the background on Android 12+. Instead the Worker runs the orchestrator directly and uses
 * this listener to surface the result to the user.
 *
 * **Design note (approach (b) from the plan):** [InferenceService]'s own notification
 * helpers are deeply entangled with Service-specific state (foreground notification id,
 * `serviceScope`, `getString` from the Service context). Extracting and delegating them
 * without a compiler would risk subtle regressions in the working ASR flow. This class
 * therefore ports ONLY the result/error notification building needed by the Worker, which
 * is an acceptable, contained duplication. The service keeps its own implementation
 * unchanged.
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
    private val resultNotificationCounter = AtomicInteger(InferenceService.RESULT_NOTIFICATION_ID)

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
        failedChunkCount: Int
    ) {
        // The worker has no Tasker reply channel; only the service sends ACTION_TASKER_REPLY.
        // For share requests, mirror the service: auto-copy (if enabled) + post the result.
        if (isShareRequest) {
            coroutineScope.launch {
                autoCopyIfEnabled(resultText, sourcePackage)
                saveTranscriptToFileIfEnabled(resultText, sourcePackage)
                showResultNotification(resultText, sourcePackage, taskId, confidence, detectedLanguage, isPartial, failedChunkCount)
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
        failedChunkCount: Int = 0
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

        val copyIntent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, transcriptionText)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            System.currentTimeMillis().toInt(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPendingIntent = buildLaunchPendingIntent(highlightTaskId = taskId)

        val isTruncated = transcriptionText.length > 100
        val previewText = if (isTruncated) transcriptionText.take(100) + "…" else transcriptionText

        val title = if (isPartial) {
            appContext.getString(R.string.transcription_partial, failedChunkCount)
        } else {
            appContext.getString(R.string.transcription_complete)
        }

        val builder = NotificationCompat.Builder(appContext, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(title)
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(transcriptionText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_save, appContext.getString(R.string.copy), copyPendingIntent)
            .setAutoCancel(true)

        if (prefs.showShareAction) {
            val useQuickShareBack = prefs.quickShareBack && sourcePackage != null
            if (useQuickShareBack) {
                val shareBackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcriptionText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val targetPackage = when {
                        sourcePackage == "com.whatsapp" || sourcePackage!!.startsWith("com.whatsapp") -> "com.whatsapp"
                        sourcePackage == "org.telegram.messenger" || sourcePackage.startsWith("org.telegram") -> "org.telegram.messenger"
                        sourcePackage == "org.thoughtcrime.securesms" -> "org.thoughtcrime.securesms"
                        else -> sourcePackage
                    }
                    setPackage(targetPackage)
                }
                val shareBackPendingIntent = PendingIntent.getActivity(
                    appContext,
                    System.currentTimeMillis().toInt() + 1,
                    shareBackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_revert,
                    AppInfoUtils.getSendToText(appContext, sourcePackage),
                    shareBackPendingIntent
                )
            } else {
                val shareChooserIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcriptionText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val sharePickerIntent = Intent.createChooser(
                    shareChooserIntent,
                    appContext.getString(R.string.share_transcription)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val sharePendingIntent = PendingIntent.getActivity(
                    appContext,
                    System.currentTimeMillis().toInt() + 1,
                    sharePickerIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_share, appContext.getString(R.string.share), sharePendingIntent)
            }
        }

        val subTextParts = mutableListOf<String>()
        if (isTruncated) {
            subTextParts.add(appContext.getString(R.string.char_counter, 100, transcriptionText.length))
        }
        val langLabel = detectedLanguage?.let { lang ->
            Language.FILTER_ENTRIES.find { it.code == lang }?.let { appContext.getString(it.nameResId) }
        }
        if (langLabel != null) {
            subTextParts.add(appContext.getString(R.string.detected_language, langLabel))
        }
        if (confidence != null && confidence < CONFIDENCE_MEDIUM_THRESHOLD) {
            subTextParts.add(appContext.getString(R.string.confidence_low))
        }
        if (subTextParts.isNotEmpty()) {
            builder.setSubText(subTextParts.joinToString(" · "))
        }

        postUniqueNotification(builder.build(), "Worker showed result notification (${transcriptionText.length} chars)")
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
        postUniqueNotification(notification, "Worker showed error notification: $errorMessage")
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
        postUniqueNotification(notification, "Worker showed no-model notification")
    }

    private fun postUniqueNotification(notification: Notification, description: String) {
        val id = resultNotificationCounter.getAndIncrement()
        notificationManager.notify(id, notification)
        Log.i(TAG, "$description (id=$id)")
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
        private const val CONFIDENCE_MEDIUM_THRESHOLD = 0.5f
        private const val RC_LAUNCH_DEFAULT = 0
        private const val RC_LAUNCH_MODEL_TAB = 1
    }
}
