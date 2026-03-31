package com.antivocale.app.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.antivocale.app.R
import com.antivocale.app.receiver.ChooserBroadcastReceiver
import com.antivocale.app.service.InferenceService
import com.antivocale.app.util.SharedAudioHandler

/**
 * Transparent activity for receiving shared audio files.
 * Handles ACTION_SEND intents with audio MIME types from other apps.
 *
 * Now includes source app detection for per-app notification preferences.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        const val TAG = "ShareReceiverActivity"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
    }

    private var sourcePackage: String? = null
    private var detectionTimeoutHandler: Handler? = null
    private var detectionTimeoutRunnable: Runnable? = null

    // Local BroadcastReceiver to receive detected package from ChooserBroadcastReceiver
    private val chosenAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val detectedPackage = intent?.getStringExtra(ChooserBroadcastReceiver.EXTRA_DETECTED_PACKAGE)
            if (detectedPackage != null) {
                Log.i(TAG, "Detected source app via ChooserBroadcastReceiver: $detectedPackage")
                sourcePackage = detectedPackage
                // Cancel timeout since we got the result
                detectionTimeoutRunnable?.let { detectionTimeoutHandler?.removeCallbacks(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Share received: action=${intent?.action}, type=${intent?.type}")

        // Register receiver to get chosen app from ChooserBroadcastReceiver
        try {
            registerReceiver(
                chosenAppReceiver,
                IntentFilter(ChooserBroadcastReceiver.ACTION_SHARE_CHOSEN),
                Context.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Registered ChooserBroadcastReceiver listener")

            // Set timeout fallback (in case BroadcastReceiver doesn't fire)
            detectionTimeoutHandler = Handler(Looper.getMainLooper())
            detectionTimeoutRunnable = Runnable {
                Log.d(TAG, "Package detection timeout - using fallback")
                unregisterReceiver(chosenAppReceiver)
            }
            detectionTimeoutHandler?.postDelayed(detectionTimeoutRunnable!!, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ChooserBroadcastReceiver listener", e)
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSendIntent(intent)
            else -> {
                Log.w(TAG, "Unexpected action: ${intent?.action}")
                cleanup()
                finish()
            }
        }
    }

    private fun handleSendIntent(intent: Intent) {
        // Try to detect the calling package (limited availability on modern Android)
        if (sourcePackage == null) {
            sourcePackage = callingActivity?.packageName
            if (sourcePackage != null) {
                Log.i(TAG, "Detected source app via callingActivity: $sourcePackage")
            }
        }

        // If still null, try getCallingPackage() for startActivityForResult scenarios
        if (sourcePackage == null) {
            @Suppress("DEPRECATION")
            sourcePackage = callingPackage
            if (sourcePackage != null) {
                Log.i(TAG, "Detected source app via callingPackage: $sourcePackage")
            }
        }

        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

        // If still null, try extracting from content URI authority
        if (sourcePackage == null && uri != null && uri.scheme == "content") {
            val authority = uri.authority
            if (authority != null) {
                // Common patterns: com.whatsapp.provider.media, org.telegram.messenger
                // Extract package from authority (e.g., "com.whatsapp.provider.media" -> "com.whatsapp")
                val detectedFromUri = when {
                    authority.startsWith("com.whatsapp") -> "com.whatsapp"
                    authority.startsWith("org.telegram") -> "org.telegram.messenger"
                    authority.startsWith("org.thoughtcrime.securesms") -> "org.thoughtcrime.securesms"
                    // If authority is already a package name
                    authority.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) -> authority
                    else -> null
                }
                if (detectedFromUri != null) {
                    sourcePackage = detectedFromUri
                    Log.i(TAG, "Detected source app from URI authority: $sourcePackage (authority: $authority)")
                }
            }
        }

        // Log detection result
        if (sourcePackage != null) {
            Log.i(TAG, "Source app detected: $sourcePackage")
        } else {
            Log.d(TAG, "Source app not detected - will use default preferences")
        }

        Log.i(TAG, "Handle share: URI=$uri, MIME=${intent.type}, source=$sourcePackage")

        if (uri == null) {
            Log.e(TAG, "No EXTRA_STREAM in intent")
            showErrorToast(getString(R.string.no_audio_file))
            cleanup()
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
            cleanup()
            finish()
            return
        }

        Log.i(TAG, "Copied to: $localPath")

        // Start service with file path and detected package
        val taskId = "share_${System.currentTimeMillis()}"

        val serviceIntent = Intent(this, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            // Pass detected source package
            sourcePackage?.let {
                putExtra(EXTRA_SOURCE_PACKAGE, it)
            }
            // Don't pass a prompt - let InferenceService use the default from settings
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_SHARE)
        }

        startForegroundService(serviceIntent)
        Log.i(TAG, "Started InferenceService for taskId: $taskId, source: $sourcePackage")

        val toastRes = if (InferenceService.isTranscribing.value)
            R.string.added_to_queue
        else
            R.string.transcription_started
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()

        cleanup()
        finish()
    }

    private fun cleanup() {
        // Unregister receiver and cancel timeout
        try {
            detectionTimeoutRunnable?.let { detectionTimeoutHandler?.removeCallbacks(it) }
            unregisterReceiver(chosenAppReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Cleanup: receiver already unregistered or never registered")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun showErrorToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
