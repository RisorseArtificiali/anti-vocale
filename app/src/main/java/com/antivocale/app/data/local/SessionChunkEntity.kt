package com.antivocale.app.data.local

import androidx.room.Entity

/**
 * TASK-1037 (GH #190): one checkpointed chunk of a resumable session.
 * The checkpoint is one row insert; the resume offset is the end of the
 * contiguous prefix ([SessionDao.contiguousPrefixEnd]), so text and
 * offset cannot drift apart.
 *
 * Offsets are 16 kHz OUTPUT samples, never chunk indexes:
 * TranscriptionMemoryPolicy may tighten the chunk size differently on
 * each resume, and sample offsets keep that law irrelevant to stitching.
 * A held empty chunk (the TASK-664 ladder) is never checkpointed, so the
 * prefix ends at the earliest unresolved chunk and that chunk decodes
 * again on resume.
 */
@Entity(tableName = "session_chunks", primaryKeys = ["taskId", "startSample"])
data class SessionChunkEntity(
    val taskId: String,
    val startSample: Long,
    val endSample: Long,
    val text: String,
    /** JSON-serialized timed cues of the chunk, when the run keeps them. */
    val cuesJson: String? = null,
    val confidence: Double? = null,
    val detectedLanguage: String? = null,
)
