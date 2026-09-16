package com.antivocale.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a completed transcription as a `.txt` file into a user-selected SAF tree.
 *
 * Pure helper: callers ([InferenceService], [com.antivocale.app.service.TranscriptionNotificationListener])
 * already run this on `Dispatchers.IO`. All failures are caught and reported as `null` so the
 * transcription flow never breaks on a revoked permission or IO error.
 */
object TranscriptFileSaver {

    private const val TAG = "TranscriptFileSaver"

    /**
     * Writes [text] to a new file under [treeUri]. [extension] and [mime] come
     * from the selected export format (GH #92); the naming scheme is unchanged,
     * only the extension varies.
     *
     * @return the written file's display name on success, or `null` on any failure.
     */
    fun save(
        context: Context,
        treeUri: Uri,
        text: String,
        sourcePackage: String? = null,
        extension: String = "txt",
        mime: String = "text/plain"
    ): String? {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            if (!tree.canWrite()) {
                Log.w(TAG, "Tree uri not writable (permission revoked?): $treeUri")
                return null
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val source = sourcePackage?.substringAfterLast('.')?.replace(".", "_") ?: "transcript"
            // Filename: {source}_{date}_{first words}.{ext}, sortable by source then date.
            val preview = text.take(30).replace(Regex("[^\\w -]"), "").trim()
                .replace(" ", "-").take(20).ifEmpty { "audio" }
            val baseName = "${source}_${timestamp}_${preview}.$extension"
            val name = uniqueName(tree, baseName)
            val file = tree.createFile(mime, name) ?: run {
                Log.w(TAG, "createFile returned null for $name")
                return null
            }
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: run {
                Log.w(TAG, "openOutputStream returned null for ${file.uri}")
                return null
            }
            file.name
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save transcript to $treeUri", e)
            null
        }
    }

    /**
     * Appends `_2`, `_3`, … before the extension if [desired] already exists in [tree].
     */
    private fun uniqueName(tree: DocumentFile, desired: String): String {
        if (tree.findFile(desired) == null) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${stem}_$i$ext"
            if (tree.findFile(candidate) == null) return candidate
            i++
        }
    }
}
