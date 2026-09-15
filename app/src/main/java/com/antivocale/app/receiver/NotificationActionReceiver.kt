package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.work.WorkManager
import com.antivocale.app.R
import com.antivocale.app.service.InferenceService
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.ShareBackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling notification actions.
 *
 * Handles:
 * - Copy transcription to clipboard
 * - Share transcription to other apps
 * - Page through long result notifications (prev/next)
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "NotificationActionReceiver"
        const val ACTION_COPY_TRANSCRIPTION = "com.antivocale.app.COPY_TRANSCRIPTION"
        const val ACTION_SHARE_TRANSCRIPTION = "com.antivocale.app.SHARE_TRANSCRIPTION"
        const val ACTION_SHARE_BACK = "com.antivocale.app.SHARE_BACK"
        const val ACTION_USE_SUBTITLES = "com.antivocale.app.USE_SUBTITLES"
        const val ACTION_TRANSCRIBE_AUDIO = "com.antivocale.app.TRANSCRIBE_AUDIO"
        /** Swipe-dismiss of the subtitle-choice prompt: cancel the fallback, start nothing. */
        const val ACTION_DISMISS_CHOICE = "com.antivocale.app.DISMISS_CHOICE"
        const val EXTRA_TRANSCRIPTION_TEXT = "transcription_text"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val ACTION_PAGE_PREV = "com.antivocale.app.PAGE_PREV"
        const val ACTION_PAGE_NEXT = "com.antivocale.app.PAGE_NEXT"
        const val EXTRA_PAGE_INDEX = "page_index"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_FIRST_POSTED_AT = "first_posted_at"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_DETECTED_LANGUAGE = "detected_language"
        const val EXTRA_IS_PARTIAL = "is_partial"
        const val EXTRA_FAILED_CHUNK_COUNT = "failed_chunk_count"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY_TRANSCRIPTION -> handleCopyAction(context, intent)
            ACTION_SHARE_TRANSCRIPTION -> handleShareAction(context, intent)
            ACTION_SHARE_BACK -> handleShareBackAction(context, intent)
            ACTION_USE_SUBTITLES -> handleSubtitleChoice(context, intent, requestType = TaskerRequestReceiver.REQUEST_TYPE_SUBTITLES)
            ACTION_DISMISS_CHOICE -> handleDismissChoice(context, intent)
            ACTION_TRANSCRIBE_AUDIO -> handleSubtitleChoice(context, intent, requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            ACTION_PAGE_PREV, ACTION_PAGE_NEXT -> handlePageAction(context, intent)
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

    /**
     * Handles a subtitle-choice notification tap: cancels the timed fallback worker
     * (either action resolves the prompt), then forwards the request to [InferenceService]
     * with the chosen [requestType] ("subtitles" or "audio"). All extras set by
     * [com.antivocale.app.receiver.ShareReceiverActivity] are passed through verbatim.
     *
     * The notification tap is user-initiated, which on Android 12+ permits starting a
     * foreground service from this broadcast context; a still-restricted start rides the
     * InferenceEnqueue trampoline notification, so the request survives either way. (The
     * timeout worker is cancelled here precisely so it does not double-run.)
     */
    private fun handleSubtitleChoice(context: Context, intent: Intent, requestType: String) {
        val taskId = intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
        if (taskId != null) {
            // Dual-cancel (code review): the path-derived name this build
            // arms, plus the legacy per-taskId name a pre-update worker may
            // still hold (in-window app update, the same window the legacy
            // notification id below already covers).
            intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)?.let {
                WorkManager.getInstance(context).cancelUniqueWork(SubtitleChoice.uniqueWorkName(it))
            }
            WorkManager.getInstance(context).cancelUniqueWork("subtitle-choice-$taskId")
            // The ONE prompt cancel (SubtitleChoice.cancelPrompt): the prompt
            // posts under the PATH-keyed id; the taskId ids are pre-TASK-440
            // legacy (round 3: this site cancelled only the legacy ids, so
            // the prompt survived its own tap with live buttons).
            SubtitleChoice.cancelPrompt(context,
                intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH), taskId)
        }

        val serviceIntent = Intent(context, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, requestType)
            intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)?.let {
                putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, it)
            }
            intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)?.let {
                putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, it)
            }
            intent.getStringExtra(EXTRA_SOURCE_PACKAGE)?.let {
                putExtra(EXTRA_SOURCE_PACKAGE, it)
            }
            intent.getStringExtra(InferenceService.EXTRA_SOURCE)?.let {
                putExtra(InferenceService.EXTRA_SOURCE, it)
            }
            intent.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE)?.let {
                putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it)
            }
            if (requestType == TaskerRequestReceiver.REQUEST_TYPE_SUBTITLES) {
                putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, trackIndexFromIntent(intent))
            }
        }

        // F6: unified enqueue; start is total (Outcome.Failed instead of throw)
        when (com.antivocale.app.service.InferenceEnqueue.start(context, serviceIntent)) {
            com.antivocale.app.service.InferenceEnqueue.Outcome.Started,
            com.antivocale.app.service.InferenceEnqueue.Outcome.FallbackNotificationPosted ->
                Log.i(TAG, "Subtitle choice '$requestType' -> enqueued InferenceService (taskId=$taskId)")
            is com.antivocale.app.service.InferenceEnqueue.Outcome.Failed ->
                Log.e(TAG, "Subtitle choice '$requestType' enqueue FAILED (taskId=$taskId): request lost")
        }
    }

    /**
     * Swipe-dismiss of the subtitle-choice prompt (TASK-378): the user
     * declined the choice, so the pending timed fallback is cancelled and
     * nothing is transcribed. No service start: a delete intent must be safe
     * to fire from anywhere.
     */
    private fun handleDismissChoice(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(com.antivocale.app.receiver.TaskerRequestReceiver.EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.d(TAG, "Dismiss choice without taskId; nothing to cancel")
            return
        }
        intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH)?.let {
            WorkManager.getInstance(context).cancelUniqueWork(SubtitleChoice.uniqueWorkName(it))
        }
        WorkManager.getInstance(context).cancelUniqueWork("subtitle-choice-$taskId")
        Log.i(TAG, "Subtitle choice dismissed by user; cancelled fallback for taskId=$taskId")
    }

    /**
     * Pages the result notification. The work runs in a coroutine because
     * [com.antivocale.app.data.PerAppPreferencesManager.getCurrentPreferences]
     * is a suspend DataStore call; if it fails, the old notification simply
     * stands (a button tap must never crash).
     */
    private fun handlePageAction(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler).launch {
            try {
                ResultNotificationRefresher.refresh(appContext, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild paged notification", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun trackIndexFromIntent(intent: Intent): Int =
        intent.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1)

    private fun handleCopyAction(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TRANSCRIPTION_TEXT)

        if (text.isNullOrBlank()) {
            Log.w(TAG, "No transcription text to copy")
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_transcription), text)
        clipboardManager.setPrimaryClip(clip)

        Log.i(TAG, "Copied transcription to clipboard (${text.length} chars)")
        com.antivocale.app.util.ToastCompat.show(context, R.string.copied_to_clipboard)
    }

    private fun handleShareAction(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TRANSCRIPTION_TEXT)

        if (text.isNullOrBlank()) {
            Log.w(TAG, "No transcription text to share")
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_transcription)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooser)
        Log.i(TAG, "Launched share chooser for transcription (${text.length} chars)")
    }

    private fun handleShareBackAction(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TRANSCRIPTION_TEXT)
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)

        if (text.isNullOrBlank()) {
            Log.w(TAG, "No transcription text to share back")
            return
        }

        // Get app name for better logging/user feedback
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(sourcePackage ?: "", 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sourcePackage ?: "App"
        }

        Log.i(TAG, "Share Back to $appName ($sourcePackage): ${text.take(50)}...")

        ShareBackHelper.shareBack(
            context = context,
            packageName = sourcePackage,
            appName = appName,
            transcriptionText = text,
            onSuccess = {
                Log.i(TAG, "Share Back initiated successfully")
                com.antivocale.app.util.ToastCompat.show(context, context.getString(R.string.share_back))
            },
            onError = { error ->
                Log.e(TAG, "Share Back failed: $error")
                com.antivocale.app.util.ToastCompat.show(context, error, Toast.LENGTH_LONG)
            }
        )
    }
}
