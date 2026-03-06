package com.localai.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for capturing the selected app from Android's share chooser.
 *
 * When a user shares content to Anti-Vocale, this receiver captures which app
 * (WhatsApp, Telegram, etc.) they selected from the chooser dialog via the
 * EXTRA_CHOSEN_COMPONENT intent extra.
 *
 * Usage:
 * 1. Create a PendingIntent targeting this receiver
 * 2. Pass it to Intent.createChooser()
 * 3. When user selects an app, this receiver fires with EXTRA_CHOSEN_COMPONENT
 * 4. Extract package name and broadcast it back to the app
 */
class ChooserBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChooserBroadcastReceiver"

        /**
         * Action broadcast when a share source app is detected
         */
        const val ACTION_SHARE_CHOSEN = "com.localai.bridge.SHARE_CHOSEN"

        /**
         * Extra containing the detected package name
         */
        const val EXTRA_DETECTED_PACKAGE = "detected_package"

        /**
         * Intent extra from Android chooser containing selected component info
         */
        private const val EXTRA_CHOSEN_COMPONENT = "android.intent.extra.CHOSEN_COMPONENT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.w(TAG, "Received null context or intent")
            return
        }

        val componentInfo = intent.extras?.get(EXTRA_CHOSEN_COMPONENT)?.toString()
        if (componentInfo.isNullOrBlank()) {
            Log.w(TAG, "No component info in chooser intent")
            return
        }

        val packageName = extractPackageName(componentInfo)
        if (packageName.isNullOrBlank()) {
            Log.w(TAG, "Failed to extract package name from: $componentInfo")
            return
        }

        Log.i(TAG, "Detected share source app: $packageName")

        // Broadcast detected package to app, restricted to our package
        val broadcastIntent = Intent(ACTION_SHARE_CHOSEN).apply {
            putExtra(EXTRA_DETECTED_PACKAGE, packageName)
            `package` = context.packageName
            // Restrict to our app only for security
            addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        }

        context.sendBroadcast(broadcastIntent)
        Log.d(TAG, "Broadcasted detected package: $packageName")
    }

    /**
     * Extract package name from ComponentInfo string.
     *
     * Input format: "ComponentInfo{com.whatsapp/com.whatsapp.ShareActivity}"
     * Output: "com.whatsapp"
     *
     * Handles malformed input gracefully by returning null.
     *
     * @param componentInfo The ComponentInfo string from EXTRA_CHOSEN_COMPONENT
     * @return The package name, or null if extraction fails
     */
    private fun extractPackageName(componentInfo: String): String? {
        return try {
            // Remove "ComponentInfo{" prefix
            val withoutPrefix = componentInfo.substringAfter("ComponentInfo{", "")

            // Extract package name (everything before the first "/")
            // Also handle closing brace "}" at the end
            val packageName = withoutPrefix
                .substringBefore("/")
                .substringBefore("}")

            // Validate package name format (should contain at least one dot)
            if (packageName.contains(".") && packageName.isNotBlank()) {
                packageName
            } else {
                Log.w(TAG, "Invalid package name format: $packageName")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting package name from: $componentInfo", e)
            null
        }
    }
}
