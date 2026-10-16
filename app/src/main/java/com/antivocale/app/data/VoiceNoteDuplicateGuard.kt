package com.antivocale.app.data

import com.antivocale.app.data.local.LogDao
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TASK-842 (GH #157 part 3): the duplicate-processing guard.
 *
 * The automatic voice-note leg (this file's shipped half): one stable
 * taskId per note ("voicenote_<PTT name>", unique per note), so a
 * completed row with the same taskId means this exact note already
 * transcribed: the guard skips it silently. There is no surface to ask on
 * in the background (the freezer constraint), and the in-flight racing
 * case is already covered by the service's own taskId dedup; this guard
 * closes the completed-row case.
 *
 * The interactive leg (the open-or-reprocess dialog at SHARE and BROWSE
 * intake; the Tasker arm is non-interactive by design and stamps the
 * hash only) rides [hashOf] +
 * LogDao.findByContentHashAndModel: filePath
 * cannot identify a re-share (sandbox copies get fresh random names), so
 * the guard hashes the CONTENT. It runs on the caller's IO dispatcher;
 * call sites must never hash on the main thread.
 */
@Singleton
class VoiceNoteDuplicateGuard @Inject constructor(
    private val logDao: LogDao,
) {

    /** A duplicate hit: the row to open, plus the model name the dialog
     *  copy shows (the DERIVED name the lookup keyed on, so both arms'
     *  dialogs agree even when the row's own stamp drifted). */
    data class DuplicateHit(
        val row: com.antivocale.app.data.local.LogEntity,
        val modelName: String,
        /** The intake hash, reused by the reprocess arm (no re-hash). */
        val contentHash: String,
    )

    /**
     * TASK-842: the intake hash on IO (the guard's own contract: hashing
     * never blocks the caller's dispatcher). Null = unreadable, or above
     * [MAX_HASHED_BYTES] (the guard is best-effort; a huge staged video
     * skips the hash rather than seconds of IO - a null hash simply means
     * the row cannot match later intakes).
     */
    suspend fun hashOnIo(filePath: String): String? = withContext(Dispatchers.IO) {
        val size = try { File(filePath).length() } catch (e: Exception) { -1L }
        if (size < 0 || size > MAX_HASHED_BYTES) null else hashOf(filePath)
    }

    /**
     * TASK-842 interactive leg, ONE owner of the lookup: the model display
     * name derives exactly as the row stamps it (displayNameForRequest),
     * and the SUCCESS row lookup keys on it. Null = no duplicate. The
     * caller owns the dialog and the activity-alive checks; pass the hash
     * from [hashOnIo] so it is computed once and reused for stamping.
     */
    suspend fun lookup(
        context: android.content.Context,
        orchestrator: com.antivocale.app.transcription.TranscriptionOrchestrator,
        contentHash: String,
        backendOverride: String? = null,
    ): DuplicateHit? {
        val modelName = orchestrator.displayNameForRequest(context, backendOverride)
        return logDao.findByContentHashAndModel(contentHash, modelName)
            ?.let { DuplicateHit(row = it, modelName = modelName, contentHash = contentHash) }
    }

    /**
     * True when this note should NOT be enqueued: a non-reprocessable
     * terminal row (SUCCESS or the user-visible ERROR) already exists for
     * the taskId. PROCESSING rows are left to the service's in-flight
     * dedup, so a restart race re-enqueues rather than silently dropping.
     */
    suspend fun alreadyTranscribed(taskId: String): Boolean {
        val row = logDao.findLatestByTaskId(taskId) ?: return false
        return row.status == "SUCCESS" || row.status == "ERROR"
    }

    companion object {
        /** TASK-842: above this the hash is skipped (a null hash disables
         *  matching for that row; the recompute it guards against is
         *  bounded anyway). 256MB covers every voice note and any sane
         *  shared audio; GB-scale staged videos skip. */
        const val MAX_HASHED_BYTES = 256L * 1024 * 1024

        /**
         * SHA-256 of a file's content, hex-encoded (the
         * ExternalModelImporter digest precedent). Call on Dispatchers.IO;
         * returns null when the file cannot be read (the guard is then
         * simply off for that intake, never a crash).
         */
        fun hashOf(path: String): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
