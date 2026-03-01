package com.localai.bridge.util

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Utility for handling shared audio files from other apps.
 *
 * Converts content:// URIs to local file paths by copying
 * the content to app-private storage.
 */
object SharedAudioHandler {

    const val TAG = "SharedAudioHandler"

    // Supported audio extensions
    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "ogg", "oga", "wav", "aac", "3gp", "flac", "opus", "amr"
    )

    // Directory name for shared audio files
    private const val SHARED_AUDIO_DIR = "shared_audio"

    /**
     * Copies a content:// URI to app-private storage.
     *
     * @param context Application context
     * @param uri Content URI from share intent
     * @param mimeType Optional MIME type (if already known from intent)
     * @return Absolute path to copied file, or null on error
     */
    fun copyToAppStorage(context: Context, uri: Uri, mimeType: String? = null): String? {
        Log.d(TAG, "copyToAppStorage: URI=$uri, MIME=$mimeType")

        return try {
            // Use provided MIME type or resolve from ContentResolver
            val resolvedMimeType = mimeType ?: context.contentResolver.getType(uri)
            Log.d(TAG, "Resolved MIME: $resolvedMimeType")

            val extension = resolveExtension(uri, resolvedMimeType)
            Log.d(TAG, "Extension: $extension")

            if (extension == null) {
                Log.e(TAG, "Could not determine file extension for URI: $uri")
                return null
            }

            if (!SUPPORTED_EXTENSIONS.contains(extension.lowercase())) {
                Log.e(TAG, "Unsupported audio format: $extension")
                return null
            }

            // Create output directory if needed
            val outputDir = File(context.filesDir, SHARED_AUDIO_DIR).apply {
                if (!exists()) mkdirs()
            }

            // Generate unique filename
            val fileName = "shared_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
            val outputFile = File(outputDir, fileName)

            // Copy content
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Could not open input stream for URI: $uri")
                return null
            }

            Log.i(TAG, "Copied ${outputFile.length()} bytes to ${outputFile.absolutePath}")
            outputFile.absolutePath

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for URI: $uri", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from URI: $uri", e)
            null
        }
    }

    /**
     * Resolves file extension from MIME type or URI.
     */
    private fun resolveExtension(uri: Uri, mimeType: String?): String? {
        // Try MIME type first
        if (!mimeType.isNullOrBlank()) {
            // Strip parameters like "; codecs=opus" from MIME type
            // e.g., "audio/ogg; codecs=opus" -> "audio/ogg"
            val baseMimeType = mimeType.split(";").first().trim()

            // Try MimeTypeMap first
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(baseMimeType)
            if (!ext.isNullOrBlank()) {
                return ext.lowercase()
            }

            // Fallback: manual mapping for common audio types
            val manualExt = when (baseMimeType.lowercase()) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a" -> "m4a"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/aac" -> "aac"
                "audio/flac" -> "flac"
                "audio/3gpp" -> "3gp"
                "audio/amr" -> "amr"
                "audio/opus" -> "opus"
                else -> null
            }
            if (!manualExt.isNullOrBlank()) {
                return manualExt.lowercase()
            }
        }

        // Fall back to URI path
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val lastDot = path.lastIndexOf('.')
            if (lastDot >= 0 && lastDot < path.length - 1) {
                return path.substring(lastDot + 1).lowercase()
            }
        }

        return null
    }

    /**
     * Cleans up old shared audio files to save storage.
     * Call periodically (e.g., on app start).
     *
     * @param context Application context
     * @param maxAgeMs Maximum age in milliseconds (default: 24 hours)
     */
    fun cleanupOldFiles(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val outputDir = File(context.filesDir, SHARED_AUDIO_DIR)
            if (!outputDir.exists()) return

            val now = System.currentTimeMillis()
            var cleaned = 0

            outputDir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > maxAgeMs) {
                    if (file.delete()) {
                        cleaned++
                    }
                }
            }

            if (cleaned > 0) {
                Log.i(TAG, "Cleaned up $cleaned old shared audio files")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old files", e)
        }
    }
}
