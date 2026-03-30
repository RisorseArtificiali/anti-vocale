package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.di.AppContainer
import com.antivocale.app.manager.LlmManager
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for preloading the model on demand.
 *
 * This receiver allows external apps (Tasker, automation apps, etc.)
 * to trigger model loading before sharing content to the app,
 * reducing latency for the first inference request.
 *
 * Usage via adb:
 *   adb shell am broadcast -a com.antivocale.app.PRELOAD_MODEL
 *
 * Usage via Tasker:
 *   Action: Send Intent
 *     Action: com.antivocale.app.PRELOAD_MODEL
 */
class ModelPreloadReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ModelPreloadReceiver"
        const val ACTION_PRELOAD_MODEL = "com.antivocale.app.PRELOAD_MODEL"

        // Optional extras
        const val EXTRA_SILENT = "silent" // If true, no reply is sent
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRELOAD_MODEL) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }

        Log.i(TAG, "Received preload model request")

        val pendingResult = goAsync()
        val isSilent = intent.getBooleanExtra(EXTRA_SILENT, false)

        CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler).launch {
            try {
                // Check if model is already loaded
                if (LlmManager.isReady()) {
                    Log.i(TAG, "Model already loaded, resetting keep-alive timer")
                    LlmManager.resetKeepAliveTimer()
                    if (!isSilent) {
                        sendReply(context, "SUCCESS", "Model already loaded")
                    }
                    return@launch
                }

                // Get saved model path from preferences
                val modelPath = AppContainer.preferencesManager.modelPath.first()

                if (modelPath.isNullOrBlank()) {
                    Log.w(TAG, "No model path configured")
                    if (!isSilent) {
                        sendReply(context, "NO_MODEL_CONFIGURED",
                            "No model path saved. Open the app to select a model.")
                    }
                    return@launch
                }

                // Validate model file exists
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    Log.w(TAG, "Model file not found: $modelPath")
                    if (!isSilent) {
                        sendReply(context, "MODEL_NOT_FOUND",
                            "Model file not found at: $modelPath")
                    }
                    return@launch
                }

                // Load the model
                Log.i(TAG, "Loading model from: $modelPath")
                val result = LlmManager.initialize(context, modelPath)

                result.fold(
                    onSuccess = {
                        Log.i(TAG, "Model loaded successfully")
                        // Apply saved keep-alive timeout
                        val timeout = AppContainer.preferencesManager.keepAliveTimeout.first()
                        LlmManager.setKeepAliveTimeout(timeout)

                        // Notify ViewModel to update UI state
                        LlmManager.notifyExternalLoad(modelPath)

                        if (!isSilent) {
                            sendReply(context, "SUCCESS", "Model loaded successfully")
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load model: ${error.message}")
                        if (!isSilent) {
                            sendReply(context, "LOAD_FAILED",
                                "Failed to load model: ${error.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during preload", e)
                if (!isSilent) {
                    sendReply(context, "ERROR", e.message ?: "Unknown error")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun sendReply(context: Context, status: String, message: String) {
        // Send a broadcast reply that Tasker can receive
        val replyIntent = Intent("com.antivocale.app.PRELOAD_RESULT").apply {
            putExtra("status", status)
            putExtra("message", message)
            setPackage(context.packageName)
        }
        context.sendBroadcast(replyIntent)
    }
}
