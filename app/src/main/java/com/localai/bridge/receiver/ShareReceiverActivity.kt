package com.localai.bridge.receiver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.localai.bridge.R
import com.localai.bridge.service.InferenceService
import com.localai.bridge.util.SharedAudioHandler

/**
 * Transparent activity for receiving shared audio files.
 * Handles ACTION_SEND intents with audio MIME types from other apps.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        const val TAG = "ShareReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Share received: action=${intent?.action}, type=${intent?.type}")

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSendIntent(intent)
            else -> {
                Log.w(TAG, "Unexpected action: ${intent?.action}")
                finish()
            }
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        Log.i(TAG, "Handle share: URI=$uri, MIME=${intent.type}")

        if (uri == null) {
            Log.e(TAG, "No EXTRA_STREAM in intent")
            showErrorToast(getString(R.string.no_audio_file))
            finish()
            return
        }

        // Copy file while Activity has URI permission
        // Content URI permissions are tied to this Activity instance
        val localPath = SharedAudioHandler.copyToAppStorage(
            applicationContext,
            uri,
            intent.type
        )

        if (localPath == null) {
            Log.e(TAG, "Failed to copy shared audio")
            showErrorToast(getString(R.string.failed_to_process_audio))
            finish()
            return
        }

        Log.i(TAG, "Copied to: $localPath")

        // Start service with file path (file is now in app-private storage)
        val taskId = "share_${System.currentTimeMillis()}"

        val serviceIntent = Intent(this, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "Transcribe this speech:")
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_SHARE)
        }

        startForegroundService(serviceIntent)
        Log.i(TAG, "Started InferenceService for taskId: $taskId")

        Toast.makeText(this, R.string.transcription_started, Toast.LENGTH_SHORT).show()

        finish()
    }

    private fun showErrorToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
