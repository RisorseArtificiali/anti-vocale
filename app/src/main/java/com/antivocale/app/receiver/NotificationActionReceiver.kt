package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.antivocale.app.R
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
        const val EXTRA_TRANSCRIPTION_TEXT = "transcription_text"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY_TRANSCRIPTION -> handleCopyAction(context, intent)
            ACTION_SHARE_TRANSCRIPTION -> handleShareAction(context, intent)
            ACTION_SHARE_BACK -> handleShareBackAction(context, intent)
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }

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
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(
                    context,
                    context.getString(R.string.share_back),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onError = { error ->
                Log.e(TAG, "Share Back failed: $error")
                Toast.makeText(
                    context,
                    error,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}
