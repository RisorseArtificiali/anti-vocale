package com.antivocale.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * TASK-1037 (GH #190): the resumable-session store. No runtime writer
 * exists until phase 3; this DAO is the persistence contract the
 * checkpoint sink and the resume path will ride.
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: TranscriptionSessionEntity)

    @Query("SELECT * FROM transcription_sessions WHERE taskId = :taskId")
    suspend fun getSession(taskId: String): TranscriptionSessionEntity?

    /**
     * Sessions whose stint is in flight right now (state ACTIVE or
     * COMPLETING): the cold-start recovery scan's input, because a death
     * during either state becomes PAUSED_SYSTEM. PAUSED_* rows are
     * deliberately excluded: they are waiting for the user, not lost.
     */
    @Query("SELECT * FROM transcription_sessions WHERE state IN ('ACTIVE', 'COMPLETING')")
    suspend fun getActiveSessions(): List<TranscriptionSessionEntity>

    /**
     * TASK-1037 phase 3: every parked session (either pause flavor), the
     * paused-notification's input. The SQL literals mirror the two
     * STATE_PAUSED_* constants by convention (Room @Query strings cannot
     * reference constants).
     */
    @Query("SELECT * FROM transcription_sessions WHERE state IN ('PAUSED_USER', 'PAUSED_SYSTEM')")
    suspend fun getPausedSessions(): List<TranscriptionSessionEntity>

    /**
     * TASK-1037 phase 3: the source files of every live session (the table
     * IS the live set; completion, Cancel and Discard all delete the row).
     * The 24h shared_audio cleanup exempts exactly these paths, or a parked
     * session's audio would be deleted out from under its Resume action
     * (the dead-Retry lesson of TASK-684).
     */
    @Query("SELECT filePath FROM transcription_sessions")
    suspend fun getLiveSourcePaths(): List<String>

    /**
     * Terminal transition: removes the session row AND cascades to its
     * chunks, atomically (a row without its chunks would resume from a
     * lie; chunks without a row are unreachable garbage).
     */
    @Transaction
    suspend fun deleteSession(taskId: String) {
        deleteChunksOf(taskId)
        deleteSessionRow(taskId)
    }

    @Query("DELETE FROM transcription_sessions WHERE taskId = :taskId")
    suspend fun deleteSessionRow(taskId: String)

    @Query("DELETE FROM session_chunks WHERE taskId = :taskId")
    suspend fun deleteChunksOf(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: SessionChunkEntity)

    @Query("SELECT * FROM session_chunks WHERE taskId = :taskId ORDER BY startSample ASC")
    suspend fun getChunks(taskId: String): List<SessionChunkEntity>

    /**
     * The contiguous-prefix chunk rows, the ONE owner of the walk rule both
     * the resume offset and the prefill content derive from: rows in
     * startSample order while chunk[i].endSample == chunk[i+1].startSample,
     * anchored at sample 0 (rows that do not cover the file from the start
     * yield an empty prefix: decode from zero, never an offset that would
     * silently skip uncovered samples). TASK-1037 phase 3 review: the rule
     * lived twice (this DAO and a copy in the orchestrator's prefill);
     * [contiguousPrefixEnd] now delegates here so it cannot drift.
     * Kotlin over the ordered read, not SQL: the walk is sequential state,
     * and SQL window functions are unavailable below API 30.
     */
    suspend fun contiguousPrefixRows(taskId: String): List<SessionChunkEntity> {
        val chunks = getChunks(taskId)
        if (chunks.isEmpty() || chunks.first().startSample != 0L) return emptyList()
        val prefix = mutableListOf(chunks.first())
        for (i in 1 until chunks.size) {
            if (chunks[i].startSample != prefix.last().endSample) break
            prefix.add(chunks[i])
        }
        return prefix
    }

    /**
     * The resume offset: the endSample of the LAST chunk in the contiguous
     * prefix ([contiguousPrefixRows]). Returns 0 when the session has no
     * anchored chunks.
     */
    suspend fun contiguousPrefixEnd(taskId: String): Long =
        contiguousPrefixRows(taskId).lastOrNull()?.endSample ?: 0L
}
