package com.antivocale.app.data

import com.antivocale.app.data.local.LogDao
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

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
 * The interactive leg (the open-or-reprocess dialog at SHARE intake
 * today; the History browse FAB and Tasker arms are unwired, documented
 * here so the gap is visible) rides [hashOf] +
 * LogDao.findByContentHashAndModel: filePath
 * cannot identify a re-share (sandbox copies get fresh random names), so
 * the guard hashes the CONTENT. It runs on the caller's IO dispatcher;
 * call sites must never hash on the main thread.
 */
@Singleton
class VoiceNoteDuplicateGuard @Inject constructor(
    private val logDao: LogDao,
) {

    /**
     * TASK-842 interactive leg: the completed row for the SAME content and
     * the SAME model, newest first; null = no duplicate. The caller owns
     * the dialog (interactive intakes only).
     */
    suspend fun findByContentHash(contentHash: String, modelName: String):
        com.antivocale.app.data.local.LogEntity? =
            logDao.findByContentHashAndModel(contentHash, modelName)

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
