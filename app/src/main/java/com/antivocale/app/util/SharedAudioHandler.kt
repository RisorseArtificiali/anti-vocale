package com.antivocale.app.util

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

    /** Video containers accepted as audio input (audio track extracted, no visual analysis).
     *  Public so the Logs tab can mark entries whose source was a video file. */
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3g2")

    /** True if [path] is a video container — the audio track is extracted from it
     *  at decode time. Used by the Logs tab to badge video-sourced transcriptions. */
    fun isVideoFile(path: String?): Boolean {
        val ext = path?.substringAfterLast('.')?.lowercase() ?: return false
        return ext in VIDEO_EXTENSIONS
    }

    // Supported extensions. Audio containers plus video containers — video is
    // treated purely as an audio source: AudioPreprocessor.extractAudioTrack()
    // selects the audio track and ignores video/subtitle tracks, so a video file
    // flows through the same decode path as audio once accepted here.
    private val SUPPORTED_EXTENSIONS = setOf(
        // Audio
        "mp3", "m4a", "ogg", "oga", "wav", "aac", "3gp", "flac", "opus", "amr"
    ) + VIDEO_EXTENSIONS

    // Directory name for shared audio files
    private const val SHARED_AUDIO_DIR = "shared_audio"

    /**
     * Result of copying a shared audio URI into app storage. Non-Success variants
     * let the share flow show a specific, user-facing message instead of a generic
     * "failed". The specific cause of each failure is logged by [copyToAppStorage]
     * at Log.e, so these variants carry only what the caller needs for the toast.
     */
    sealed class CopyResult {
        data class Success(val path: String) : CopyResult()
        /** The shared content had no recognizable audio extension/MIME. */
        object UnknownFormat : CopyResult()
        /** Format was identified but is not in the accepted set. [extension] is the raw ext (no dot). */
        data class UnsupportedFormat(val extension: String) : CopyResult()
        /** The content could not be read (permission, I/O, empty stream). */
        object Unreadable : CopyResult()
        /** Target storage cannot hold the source plus margin (TASK-432 pre-copy gate). */
        data class OutOfSpace(val neededMb: Int) : CopyResult()
    }

    /** Bytes the target storage must hold for a source of [neededBytes]: source plus margin (10% + 32MB). */
    internal fun requiredBytes(neededBytes: Long): Long =
        neededBytes + neededBytes / 10L + 32L * 1024 * 1024L

    /** True when the target storage can hold the source plus margin. */
    internal fun hasFreeSpace(availableBytes: Long, neededBytes: Long): Boolean =
        availableBytes >= requiredBytes(neededBytes)

    internal fun neededMb(neededBytes: Long): Int =
        (requiredBytes(neededBytes) / (1024L * 1024L)).toInt()

    /**
     * Copies a content:// URI to app-private storage.
     *
     * @param context Application context
     * @param uri Content URI from share intent
     * @param mimeType Optional MIME type (if already known from intent)
     * @return [CopyResult]; [CopyResult.Success] yields the local path, the others
     *   describe the specific failure for a clear user-facing message.
     */
    fun copyToAppStorage(context: Context, uri: Uri, mimeType: String? = null): CopyResult {
        Log.d(TAG, "copyToAppStorage: URI=$uri, MIME=$mimeType")

        return try {
            // Use provided MIME type or resolve from ContentResolver
            val resolvedMimeType = try {
                mimeType ?: context.contentResolver.getType(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve MIME for URI: $uri", e)
                null
            }
            Log.d(TAG, "Resolved MIME: $resolvedMimeType")

            val extension = resolveExtension(uri, resolvedMimeType)
            Log.d(TAG, "Extension: $extension")

            if (extension == null) {
                Log.e(TAG, "Could not determine file extension for URI: $uri")
                return CopyResult.UnknownFormat
            }

            if (!SUPPORTED_EXTENSIONS.contains(extension.lowercase())) {
                Log.e(TAG, "Unsupported audio format: $extension")
                return CopyResult.UnsupportedFormat(extension)
            }

            // Create output directory if needed
            val outputDir = File(context.filesDir, SHARED_AUDIO_DIR).apply {
                if (!exists()) mkdirs()
            }

            // Generate unique filename
            val fileName = "shared_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
            val outputFile = File(outputDir, fileName)

            // Pre-copy storage gate + copy over ONE provider session (TASK-432:
            // with the 2GB sanity bound, a near-full device would otherwise hit
            // ENOSPC mid-copy). Size and stream both come from the
            // AssetFileDescriptor when the provider offers one; unknown size
            // fails open (the copy itself will error visibly).
            try {
                val afd = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open AssetFileDescriptor for URI: $uri", e)
                    null
                }
                if (afd != null) {
                    afd.use {
                        val neededBytes = it.length
                        if (neededBytes > 0 && !hasFreeSpace(context.filesDir.usableSpace, neededBytes)) {
                            Log.e(TAG, "Not enough free space for $uri: needs $neededBytes bytes")
                            return CopyResult.OutOfSpace(neededMb(neededBytes))
                        }
                        it.createInputStream()?.use { input ->
                            FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                        } ?: throw java.io.FileNotFoundException("AssetFileDescriptor gave no stream")
                    }
                } else {
                    // Providers without AFD support: fall back to the plain stream.
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: run {
                        Log.e(TAG, "Could not open input stream for URI: $uri")
                        outputFile.delete()
                        return CopyResult.Unreadable
                    }
                }
            } catch (e: Exception) {
                // Clean up any partially-written file so a flaky/revoked content provider
                // can't leak partial files into shared_audio/ until the 24h cleanup runs.
                outputFile.delete()
                Log.e(TAG, "Error copying file from URI: $uri", e)
                return CopyResult.Unreadable
            }

            if (outputFile.length() == 0L) {
                outputFile.delete()
                Log.e(TAG, "Shared content was empty for URI: $uri")
                return CopyResult.Unreadable
            }

            Log.i(TAG, "Copied ${outputFile.length()} bytes to ${outputFile.absolutePath}")
            CopyResult.Success(outputFile.absolutePath)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for URI: $uri", e)
            CopyResult.Unreadable
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from URI: $uri", e)
            CopyResult.Unreadable
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

            // Fallback: manual mapping for common audio and video container types.
            // Covers MIME types that MimeTypeMap does not resolve and the
            // application/* misclassification some senders apply to video shares.
            val manualExt = when (baseMimeType.lowercase()) {
                // Audio
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a" -> "m4a"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/aac" -> "aac"
                "audio/flac" -> "flac"
                "audio/3gpp" -> "3gp"
                "audio/amr" -> "amr"
                "audio/opus" -> "opus"
                // Video (audio container only). Keep in sync with VIDEO_EXTENSIONS above.
                "video/mp4" -> "mp4"
                "video/m4v" -> "m4v"
                "video/x-matroska", "application/x-matroska" -> "mkv"
                "video/webm" -> "webm"
                "video/quicktime" -> "mov"
                "video/3gpp2" -> "3g2"
                // Some senders tag .mp4 shares as application/mp4; without this the
                // file resolves to null and is rejected despite valid bytes.
                "application/mp4" -> "mp4"
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
