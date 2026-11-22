package com.antivocale.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TASK-1037 (GH #190): one resumable transcription session, keyed by the
 * orchestrator task id. A row exists exactly while the session is in
 * flight: completion, Cancel and Discard all delete it ([SessionDao.deleteSession]),
 * so the table IS the set of live sessions.
 *
 * The identity columns are FROZEN at creation: resume re-reads them and
 * refuses on a mismatch (file fingerprint, model, language), instead of
 * stitching stints transcribed under different conditions.
 */
@Entity(tableName = "transcription_sessions")
data class TranscriptionSessionEntity(
    @PrimaryKey val taskId: String,
    // Identity, frozen at creation.
    val filePath: String,
    val fileSizeBytes: Long,
    val fileMtimeMs: Long,
    val backendId: String,
    val modelPath: String,
    val languagePin: String? = null,
    val prompt: String = "",
    val source: String,
    val sourcePackage: String? = null,
    val senderName: String? = null,
    // Progress.
    /** One of the [STATE_*] constants; see the plan's state machine. */
    val state: String,
    /** Total 16 kHz output samples when the decode header reported it; null when unknown. */
    val totalOutputSamples: Long? = null,
    val accumulatedComputeMs: Long = 0,
    // Bookkeeping.
    val failedChunkCount: Int = 0,
    val blankChunkCount: Int = 0,
    val retriedChunkCount: Int = 0,
    /** Native crashes counted at the SAME resume offset; the crash-loop guard's counter. */
    val crashAtOffsetCount: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    companion object {
        /**
         * The state machine's non-paused in-flight states. The DAO's SQL
         * literals stay in sync with these by convention (Room @Query
         * strings cannot reference constants).
         */
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_PAUSED_USER = "PAUSED_USER"
        const val STATE_PAUSED_SYSTEM = "PAUSED_SYSTEM"
        const val STATE_COMPLETING = "COMPLETING"
    }
}
