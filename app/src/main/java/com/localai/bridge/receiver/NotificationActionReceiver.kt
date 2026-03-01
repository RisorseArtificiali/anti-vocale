package com.localai.bridge.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.localai.bridge.R

/**
 * BroadcastReceiver for handling notification actions.
 *
 * Handles:
 * - Copy transcription to clipboard
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "NotificationActionReceiver"
        const val ACTION_COPY_TRANSCRIPTION = "com.localai.bridge.COPY_TRANSCRIPTION"
        const val EXTRA_TRANSCRIPTION_TEXT = "transcription_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY_TRANSCRIPTION -> handleCopyAction(context, intent)
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
        val clip = ClipData.newPlainText("Transcription", text)
        clipboardManager.setPrimaryClip(clip)

        Log.i(TAG, "Copied transcription to clipboard (${text.length} chars)")
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
