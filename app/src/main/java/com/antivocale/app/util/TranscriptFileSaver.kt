package com.antivocale.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.antivocale.app.transcription.TimedSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a completed transcription into a user-selected SAF tree as plain
 * text or, per the export format (GH #92), SRT/WebVTT.
 *
 * Pure helper: callers ([InferenceService], [com.antivocale.app.service.TranscriptionNotificationListener])
 * already run this on `Dispatchers.IO`. All failures are caught and reported as `null` so the
 * transcription flow never breaks on a revoked permission or IO error.
 */
object TranscriptFileSaver {

    private const val TAG = "TranscriptFileSaver"

    /**
     * The auto-save entry point (GH #92): applies the fail-safe
     * ([SubtitleFormatter.resolveExport], the one place that decides whether a
     * timed format may be written) and writes the resulting content. Both
     * mirrored save sites call this so the never-write-bogus-timing invariant
     * has a single owner.
     *
     * @return the written file's display name on success, or `null` on any failure.
     */
    fun saveAuto(
        context: Context,
        treeUriString: String?,
        storedFormat: String?,
        transcript: String,
        segments: List<TimedSegment>,
        failedChunkCount: Int,
        sourcePackage: String? = null,
    ): String? {
        if (treeUriString.isNullOrBlank()) return null
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.fromStored(storedFormat), transcript, segments, failedChunkCount,
        )
        // The "first words" preview must come from the transcript: the
        // formatted payload opens with timestamps ("WEBVTT", "00:00:01")
        // and names the file after the clock instead of the words.
        return save(context, Uri.parse(treeUriString), decision.format, decision.content, sourcePackage, transcript)
    }

    /**
     * Writes [text] to a new file under [treeUri] in [format]. Taking the
     * RESOLVED [SubtitleFormatter.Format] (not extension/mime strings) keeps
     * the fail-safe un-bypassable: a caller cannot smuggle a timed extension
     * past resolveExport.
     *
     * @return the written file's display name on success, or `null` on any failure.
     */
    private fun save(
        context: Context,
        treeUri: Uri,
        format: SubtitleFormatter.Format,
        text: String,
        sourcePackage: String?,
        namePreview: String,
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
            val preview = namePreview.take(30).replace(Regex("[^\\w -]"), "").trim()
                .replace(" ", "-").take(20).ifEmpty { "audio" }
            val baseName = "${source}_${timestamp}_${preview}.${format.extension}"
            val name = uniqueName(tree, baseName)
            val file = tree.createFile(format.mime, name) ?: run {
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
