package com.antivocale.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.util.TranscriptSignature
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
     * @return [SaveResult]: [SaveResult.Saved] with the display name on success, a
     *  [SaveResult.Failed] naming the failing step otherwise.
     */
    fun saveAuto(
        context: Context,
        treeUriString: String?,
        storedFormat: String?,
        transcript: String,
        segments: List<TimedSegment>,
        failedChunkCount: Int,
        sourcePackage: String? = null,
        /** TASK-647: the AI-disclaimer signature for the exported file; blank = off. */
        signature: String = "",
        signaturePosition: String = "append",
    ): SaveResult {
        if (treeUriString.isNullOrBlank()) {
            android.util.Log.w(TAG, "Auto-save skipped: no output folder configured")
            return SaveResult.NotConfigured
        }
        val decision = SubtitleFormatter.resolveExport(
            SubtitleFormatter.Format.fromStored(storedFormat), transcript, segments, failedChunkCount,
        )
        val content = signedExport(decision, signature, signaturePosition)
        // The "first words" preview must come from the transcript: the
        // formatted payload opens with timestamps ("WEBVTT", "00:00:01")
        // and names the file after the clock instead of the words.
        return save(context, Uri.parse(treeUriString), decision.format, content, sourcePackage, transcript)
    }

    /**
     * TASK-722: auto-save outcomes. Every failure is USER-VISIBLE now (the
     * reporter of the Android 16/OnePlus report saw transcription + History
     * and no file, with the reason buried in logcat): [failureReason] rides
     * the result notification subtext.
     */
    sealed class SaveResult {
        data class Saved(val name: String) : SaveResult()
        data class Failed(val failureReason: String) : SaveResult()
        data object NotConfigured : SaveResult()

        companion object {
            /** One token per failing step, single-named so tests and the
             *  notification layer can key on them (TASK-722). */
            const val FAIL_FOLDER_UNAVAILABLE = "folder_unavailable"
            const val FAIL_NOT_WRITABLE = "not_writable"
            const val FAIL_CREATE_REFUSED = "create_refused"
            const val FAIL_OPEN_STREAM = "open_stream_failed"
            /** The catch-all: the exception's class name stays in logcat (the
             *  full stack is logged there); the user token is generic. */
            const val FAIL_EXCEPTION = "write_failed"
        }

        /** The failure reason when this is a [Failed], null otherwise: the
         *  one-line reduction both service save sites use (their duplicated
         *  when-blocks collapse to this). */
        fun failureOrNull(): String? = (this as? Failed)?.failureReason
    }

    /** TASK-647: the disclaimer header for timed formats (a comment block
     *  every player renders or safely ignores; VTT has NOTE, SRT has no
     *  official comment so a blank-separated line is the convention). */
    /**
     * TASK-647: the export's signed content. Plain text rides the signature
     * inline (the same assembly as copy/share); the timed subtitle formats
     * take it as a leading NOTE/comment block instead, never inline (a
     * stray line would corrupt cue timing/rendering). Pure and tested.
     */
    internal fun signedExport(
        decision: SubtitleFormatter.ExportDecision,
        signature: String,
        signaturePosition: String,
    ): String = if (signature.isBlank()) decision.content else when (decision.format) {
        SubtitleFormatter.Format.TXT, SubtitleFormatter.Format.TXT_TIMED ->
            TranscriptSignature.apply(decision.content, signature, signaturePosition)
        SubtitleFormatter.Format.SRT, SubtitleFormatter.Format.VTT ->
            noteBlock(decision.format, signature, decision.content)
    }

    /**
     * The signature must be ONE line without the cue separator: a multi-line
     * signature (the field allows it) would end the VTT NOTE block early,
     * and '-->' anywhere invalidates WebVTT (strict parsers reject the file).
     */
    private fun sanitized(signature: String): String =
        signature.replace("\r?\n".toRegex(), " ").replace("-->", "-")

    private fun noteBlock(format: SubtitleFormatter.Format, signature: String, content: String): String =
        if (format == SubtitleFormatter.Format.VTT) {
            // WEBVTT must stay the first line; the NOTE goes right after it.
            val headerEnd = content.indexOf('\n')
            if (headerEnd < 0) content else {
                content.substring(0, headerEnd + 1) + "NOTE ${sanitized(signature)}\n\n" + content.substring(headerEnd + 1)
            }
        } else {
            // SRT has no official comment: a plain line before the first cue
            // is the convention players ignore or show as a banner.
            "$signature\n\n$content"
        }

    /**
     * Writes [text] to a new file under [treeUri] in [format]. Taking the
     * RESOLVED [SubtitleFormatter.Format] (not extension/mime strings) keeps
     * the fail-safe un-bypassable: a caller cannot smuggle a timed extension
     * past resolveExport.
     *
     * @return [SaveResult]: [SaveResult.Saved] with the display name on success, a
     *  [SaveResult.Failed] naming the failing step otherwise.
     */
    private fun save(
        context: Context,
        treeUri: Uri,
        format: SubtitleFormatter.Format,
        text: String,
        sourcePackage: String?,
        namePreview: String,
    ): SaveResult {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: run {
                Log.w(TAG, "fromTreeUri returned null for $treeUri (revoked or virtual tree?)")
                return SaveResult.Failed(SaveResult.FAIL_FOLDER_UNAVAILABLE)
            }
            if (!tree.canWrite()) {
                Log.w(TAG, "Tree uri not writable (permission revoked?): $treeUri")
                return SaveResult.Failed(SaveResult.FAIL_NOT_WRITABLE)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val source = sourcePackage?.substringAfterLast('.')?.replace(".", "_") ?: "transcript"
            // Filename: {source}_{date}_{first words}.{ext}, sortable by source then date.
            val preview = namePreview.take(30).replace(Regex("[^\\w -]"), "").trim()
                .replace(" ", "-").take(20).ifEmpty { "audio" }
            val baseName = "${source}_${timestamp}_${preview}.${format.extension}"
            val name = uniqueName(tree, baseName)
            val file = tree.createFile(format.mime, name) ?: run {
                // TASK-722: the prime Android-16/OnePlus suspect, a provider
                // root (e.g. Downloads) that accepts the grant but refuses
                // creation. The reason names WHICH step died.
                Log.w(TAG, "createFile returned null for $name")
                return SaveResult.Failed(SaveResult.FAIL_CREATE_REFUSED)
            }
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: run {
                Log.w(TAG, "openOutputStream returned null for ${file.uri}")
                return SaveResult.Failed(SaveResult.FAIL_OPEN_STREAM)
            }
            // TASK-722 review: the affirmative success trace lives at the
            // single owner (both service twins collapsed their copies).
            Log.i(TAG, "Saved transcript to output folder: ${file.name ?: name}")
            SaveResult.Saved(file.name ?: name)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save transcript to $treeUri", e)
            SaveResult.Failed(SaveResult.FAIL_EXCEPTION)
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
