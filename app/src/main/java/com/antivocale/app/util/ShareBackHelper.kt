package com.antivocale.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.R

/**
 * Helper for sharing transcriptions back to messaging apps.
 *
 * Supports direct text injection for WhatsApp and Telegram,
 * with clipboard fallback for other apps.
 */
object ShareBackHelper {
    private const val TAG = "ShareBackHelper"

    /**
     * Share transcription back to the source app.
     *
     * @param context Application context
     * @param packageName Source app package name
     * @param appName Source app display name
     * @param transcriptionText Text to share
     * @param onSuccess Callback when share action is initiated
     * @param onError Callback when share action fails
     */
    fun shareBack(
        context: Context,
        packageName: String?,
        appName: String?,
        transcriptionText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (packageName == null) {
            onError("No source app detected")
            return
        }

        // Check if this app supports direct text injection
        when (packageName) {
            PerAppPreferencesManager.WHATSAPP -> shareToWhatsApp(context, transcriptionText, onSuccess, onError)
            PerAppPreferencesManager.TELEGRAM -> shareToTelegram(context, transcriptionText, onSuccess, onError)
            PerAppPreferencesManager.SIGNAL -> shareToSignal(context, transcriptionText, onSuccess, onError)
            else -> shareToGenericApp(context, packageName, appName ?: packageName, transcriptionText, onSuccess, onError)
        }
    }

    /**
     * Share text to WhatsApp.
     */
    private fun shareToWhatsApp(
        context: Context,
        text: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(PerAppPreferencesManager.WHATSAPP)
                // Add flags to start fresh conversation
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            context.startActivity(intent)
            Log.i(TAG, "Shared text to WhatsApp: ${text.take(50)}...")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share to WhatsApp", e)
            // Fall back to clipboard
            copyToClipboard(context, text, onSuccess)
        }
    }

    /**
     * Share text to Telegram.
     */
    private fun shareToTelegram(
        context: Context,
        text: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(PerAppPreferencesManager.TELEGRAM)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            context.startActivity(intent)
            Log.i(TAG, "Shared text to Telegram: ${text.take(50)}...")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share to Telegram", e)
            // Fall back to clipboard
            copyToClipboard(context, text, onSuccess)
        }
    }

    /**
     * Share text to Signal (fallback to clipboard).
     */
    private fun shareToSignal(
        context: Context,
        text: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Signal doesn't reliably support direct text injection,
        // so we copy to clipboard and open the app
        copyToClipboard(context, text) {
            // Try to open Signal app
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(PerAppPreferencesManager.SIGNAL)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open Signal app", e)
            }
            onSuccess()
        }
    }

    /**
     * Share text to a generic/unknown app.
     */
    private fun shareToGenericApp(
        context: Context,
        packageName: String,
        appName: String,
        text: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            context.startActivity(intent)
            Log.i(TAG, "Shared text to $appName: ${text.take(50)}...")
            onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share to $appName", e)
            // Fall back to clipboard
            copyToClipboard(context, text, onSuccess)
        }
    }

    /**
     * Copy text to clipboard and show toast notification.
     */
    private fun copyToClipboard(
        context: Context,
        text: String,
        onComplete: () -> Unit
    ) {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Transcription", text)
            clipboardManager.setPrimaryClip(clip)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    R.string.copied_to_clipboard,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            Log.i(TAG, "Text copied to clipboard: ${text.take(50)}...")
            onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
