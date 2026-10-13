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
        /**
         * GH #128: [folderName] says WHERE (a twin folder reads right here:
         * two granted trees can share a display name). TASK-798:
         * [fileName] is the provider's name for the written document (it
         * may differ from the requested one); surfaces on the notification
         * so a user can search for the exact file.
         */
        data class Saved(val folderName: String, val fileName: String) : SaveResult()
        data class Failed(val failureReason: String) : SaveResult()
        data object NotConfigured : SaveResult()

        companion object {
            /** One token per failing step, single-named so tests and the
             *  notification layer can key on them (TASK-722). */
            const val FAIL_FOLDER_UNAVAILABLE = "folder_unavailable"
            const val FAIL_NOT_WRITABLE = "not_writable"
            const val FAIL_CREATE_REFUSED = "create_refused"
            const val FAIL_OPEN_STREAM = "open_stream_failed"
            /** TASK-798: the write "succeeded" but the provider reports
             *  the document empty or gone (virtual providers discard
             *  silently). */
            const val FAIL_VERIFY_EMPTY = "verify_empty"

            /** The catch-all: the exception's class name stays in logcat (the
             *  full stack is logged there); the user token is generic. */
            const val FAIL_EXCEPTION = "write_failed"
        }

        /** The failure reason when this is a [Failed], null otherwise: the
         *  one-line reduction both service save sites use (their duplicated
         *  when-blocks collapse to this). */
        fun failureOrNull(): String? = (this as? Failed)?.failureReason

        /** The destination folder's display name when this is a [Saved]
         *  (null when the provider gave none), null otherwise: the success
         *  twin of [failureOrNull]. */
        fun savedFolderOrNull(): String? = (this as? Saved)?.folderName

        /** TASK-798: the provider-side file name on Saved, null otherwise. */
        fun savedFileNameOrNull(): String? = (this as? Saved)?.fileName
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
            // TASK-798 (GH #128): a provider can accept createFile AND the
            // write yet never materialize the file (virtual roots do this);
            // the user saw "saved" notifications with no file on disk for
            // months. Verify the provider's own view of the document after
            // writing through the pure decision below (unit-tested): a
            // reported zero, or a vanished document, is a FAILURE; an
            // unknown size (COLUMN_SIZE null is contract-legal for
            // cloud/async providers) or an empty export is a logged pass.
            // TASK-820 device finding (2026-10-13): the DOWNLOADS tree's
            // size query can return NO ROW for a document it just created
            // (provider lag: the file was on disk, 84 bytes, while the query
            // missed it). A Missing verdict therefore gets one delayed
            // re-query, then the honest fallback: read the content BACK
            // through the provider's own stream (a real non-write still
            // fails there, which is what this verification exists to catch).
            when (VerifyDecision.decide(reportedSize(context, file.uri), text.length)) {
                VerifyDecision.Outcome.PASS -> Unit
                VerifyDecision.Outcome.CANNOT_VERIFY ->
                    Log.i(TAG, "Provider reports unknown size for ${file.uri}; cannot verify the write")
                VerifyDecision.Outcome.FAIL -> {
                    Thread.sleep(300)
                    when (VerifyDecision.decide(reportedSize(context, file.uri), text.length)) {
                        VerifyDecision.Outcome.PASS -> Unit
                        VerifyDecision.Outcome.CANNOT_VERIFY ->
                            Log.i(TAG, "Provider reports unknown size for ${file.uri}; cannot verify the write")
                        VerifyDecision.Outcome.FAIL -> {
                            val readBack = try {
                                context.contentResolver.openInputStream(file.uri)?.use { input ->
                                    var n = 0
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        n += read
                                    }
                                    n
                                } ?: -1
                            } catch (e: Exception) {
                                Log.w(TAG, "Verification stream failed for ${file.uri}", e)
                                -1
                            }
                            if (readBack >= text.length) {
                                Log.i(TAG, "Verify read the content back through the stream ($readBack bytes); " +
                                    "the provider's size view lagged")
                            } else {
                                Log.w(TAG, "Verification read $readBack of ${text.length} bytes for ${file.uri}")
                                return SaveResult.Failed(SaveResult.FAIL_VERIFY_EMPTY)
                            }
                        }
                    }
                }
            }
            // TASK-722 review: the affirmative success trace lives at the
            // single owner (both service twins collapsed their copies).
            // GH #128 (review): the destination rides BOTH surfaces through
            // the ONE owner (TreeUris.displayPath since the 2026-10-07 road
            // test: the full path where the URI carries one, the display
            // name otherwise, SAME derivation as the Settings export card),
            // and the success log carries the tree itself so a twin-folder
            // report pins to a concrete destination.
            val actualName = file.name ?: name
            Log.i(TAG, "Saved transcript to output folder: $actualName (tree=$treeUri)")
            SaveResult.Saved(TreeUris.displayPath(context, treeUri), actualName)
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

    /**
     * TASK-798: the provider's own size report for a document, THREE-way:
     * null row/cursor = [SizeReport.Missing], null COLUMN_SIZE (contract-
     * legal "unknown") = [SizeReport.Unknown], else [SizeReport.Bytes].
     * DocumentsContract only; a query failure reads as Unknown (the
     * documentfile 1.0.0 length() collapses all three to 0, which is why
     * this exists).
     */
    private fun reportedSize(context: Context, uri: Uri): SizeReport = try {
        val cursor = context.contentResolver.query(
            uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE),
            null, null, null)
        if (cursor == null) {
            SizeReport.Unknown
        } else {
            cursor.use { c ->
                when {
                    !c.moveToFirst() -> SizeReport.Missing
                    c.isNull(0) -> SizeReport.Unknown
                    else -> SizeReport.Bytes(c.getLong(0))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Size query failed for $uri; treating as unknown", e)
        SizeReport.Unknown
    }

    /** TASK-798: see [reportedSize]. */
    internal sealed interface SizeReport {
        data object Missing : SizeReport
        data object Unknown : SizeReport
        data class Bytes(val bytes: Long) : SizeReport
    }

    /**
     * TASK-798: the pure post-write decision (unit-tested): reported zero
     * bytes or a vanished document with non-empty content is the GH #128
     * silent data-loss class; unknown size cannot verify (logged pass);
     * empty exports legitimately report zero.
     */
    internal object VerifyDecision {
        enum class Outcome { PASS, CANNOT_VERIFY, FAIL }

        fun decide(report: SizeReport, textLength: Int): Outcome = when (report) {
            SizeReport.Unknown -> Outcome.CANNOT_VERIFY
            SizeReport.Missing ->
                if (textLength > 0) Outcome.FAIL else Outcome.PASS
            is SizeReport.Bytes ->
                // Only an explicit 0 fails: some off-contract providers
                // report -1 for "unknown/pending" and a negative must not
                // false-fail a write that landed.
                if (report.bytes == 0L && textLength > 0) Outcome.FAIL
                else if (report.bytes < 0) Outcome.CANNOT_VERIFY
                else Outcome.PASS
        }
    }
}
