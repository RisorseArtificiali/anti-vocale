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
import com.antivocale.app.util.ShareBackHelper

/**
 * BroadcastReceiver for handling notification actions.
 *
 * Handles:
 * - Copy transcription to clipboard
 * - Share transcription to other apps
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "NotificationActionReceiver"
        const val ACTION_COPY_TRANSCRIPTION = "com.antivocale.app.COPY_TRANSCRIPTION"
        const val ACTION_SHARE_TRANSCRIPTION = "com.antivocale.app.SHARE_TRANSCRIPTION"
        const val ACTION_SHARE_BACK = "com.antivocale.app.SHARE_BACK"
        const val ACTION_USE_SUBTITLES = "com.antivocale.app.USE_SUBTITLES"
        const val ACTION_TRANSCRIBE_AUDIO = "com.antivocale.app.TRANSCRIBE_AUDIO"
        const val EXTRA_TRANSCRIPTION_TEXT = "transcription_text"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY_TRANSCRIPTION -> handleCopyAction(context, intent)
            ACTION_SHARE_TRANSCRIPTION -> handleShareAction(context, intent)
            ACTION_SHARE_BACK -> handleShareBackAction(context, intent)
            ACTION_USE_SUBTITLES -> handleSubtitleChoice(context, intent, requestType = "subtitles")
            ACTION_TRANSCRIBE_AUDIO -> handleSubtitleChoice(context, intent, requestType = "audio")
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

    /**
     * Handles a subtitle-choice notification tap: cancels the 5-minute timeout worker
     * (either action resolves the prompt), then forwards the request to [InferenceService]
     * with the chosen [requestType] ("subtitles" or "audio"). All extras set by
     * [com.antivocale.app.receiver.ShareReceiverActivity] are passed through verbatim.
     *
     * The notification tap is user-initiated, which on Android 12+ permits starting a
     * foreground service from this broadcast context. If the OEM still blocks it, the
     * worst case is the request does not run — the user can re-share. (The timeout worker
     * is cancelled here precisely so it does not double-run.)
     */
    private fun handleSubtitleChoice(context: Context, intent: Intent, requestType: String) {
        val taskId = intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)
        if (taskId != null) {
            WorkManager.getInstance(context).cancelUniqueWork("subtitle-choice-$taskId")
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
            if (requestType == "subtitles") {
                putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, trackIndexFromIntent(intent))
            }
        }

        try {
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "Subtitle choice '$requestType' → started InferenceService (taskId=$taskId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start InferenceService for subtitle choice '$requestType'", e)
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
