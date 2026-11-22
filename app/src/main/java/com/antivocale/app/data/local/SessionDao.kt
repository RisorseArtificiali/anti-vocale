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
     * The resume offset: the endSample of the LAST chunk in the
     * contiguous prefix, where chunk[i].endSample == chunk[i+1].startSample.
     * The prefix is anchored at sample 0: rows that do not cover the file
     * from the start yield 0 (decode from zero) rather than an offset
     * that would silently skip uncovered samples. Returns 0 when the
     * session has no chunks. Kotlin over the ordered read, not SQL: the
     * walk is sequential state, and SQL window functions are unavailable
     * below API 30.
     */
    suspend fun contiguousPrefixEnd(taskId: String): Long {
        val chunks = getChunks(taskId)
        if (chunks.isEmpty() || chunks.first().startSample != 0L) return 0L
        var end = chunks.first().endSample
        for (i in 1 until chunks.size) {
            if (chunks[i].startSample != end) break
            end = chunks[i].endSample
        }
        return end
    }
}
