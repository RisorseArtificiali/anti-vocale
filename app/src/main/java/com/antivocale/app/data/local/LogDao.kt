package com.antivocale.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LogEntity)

    @Update
    suspend fun update(log: LogEntity)

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM logs")
    suspend fun deleteAll()

    /**
     * Bounded recent-first query for the Logs UI (TASK-340 Fix 2a): the previous
     * unbounded getAll let the whole history pile into the 256MB heap, and the
     * ViewModel remaps the full list into new objects on every interim Room write.
     * 500 = the bounded UI window; the search query below reaches FULL history
     * (SQL LIKE) so the bound does not silently hide older transcripts from search.
     */
    /**
     * The two queries below list every [LogEntity] column EXCEPT `segments`
     * (GH #92): the cues JSON re-copies the transcript per timed row, and these
     * queries re-emit on every table write (including per-chunk interim
     * updates), so the list paths must not carry it. Room fills the unselected
     * nullable column with its null default (partial-entity query) and
     * validates each column name at compile time. When a column is added to
     * LogEntity, add it to BOTH lists or it silently reads as its default in
     * the Logs list.
     */
    @Query("SELECT id, timestamp, taskId, type, status, prompt, result, errorMessage, durationMs, " +
        "filePath, audioDurationSeconds, sourcePackageName, isPartial, failedChunkCount, " +
        "modelName, rawTranscript, summary, summarySkipReason, failureContext FROM logs ORDER BY timestamp DESC LIMIT 500")
    fun getAll(): Flow<List<LogEntity>>

    @Query("SELECT id, timestamp, taskId, type, status, prompt, result, errorMessage, durationMs, " +
        "filePath, audioDurationSeconds, sourcePackageName, isPartial, failedChunkCount, " +
        "modelName, rawTranscript, summary, summarySkipReason, failureContext FROM logs WHERE result LIKE '%' || :query || '%' " +
        "ORDER BY timestamp DESC LIMIT 500")
    fun searchAll(query: String): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): LogEntity?

    /** Records which model handled a task (GH #45); written as soon as the backend is loaded. */
    @Query("UPDATE logs SET modelName = :modelName WHERE taskId = :taskId")
    suspend fun setModelName(taskId: String, modelName: String)

    // ---- GH #51 status transitions ----
    // The SQL IN-lists below are the single source for the non-terminal set,
    // including the legacy "PENDING" spelling written before the QUEUED/PROCESSING
    // split; Kotlin callers must not keep their own copy.

    /** Promotes a row to PROCESSING when work starts; terminal rows (and absent rows) are untouched. */
    @Query("UPDATE logs SET status = 'PROCESSING' WHERE taskId = :taskId AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun promoteToProcessing(taskId: String)

    /** Fails a single non-terminal row (cancellation / interruption paths). */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :errorMessage, durationMs = :durationMs WHERE taskId = :taskId AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failNonTerminal(taskId: String, errorMessage: String, durationMs: Long)

    /** Fails the non-terminal rows of the given tasks in one round trip (batch cancel). */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :errorMessage WHERE taskId IN (:taskIds) AND status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failNonTerminalForTaskIds(taskIds: List<String>, errorMessage: String)

    /**
     * Fails every non-terminal row at once (cold-start sweep): rows left QUEUED or
     * PROCESSING (or the legacy "PENDING") by a process death can never complete.
     * Safe to run only at process start, before the transcription service can be
     * running in this same process.
     */
    @Query("UPDATE logs SET status = 'ERROR', errorMessage = :reason WHERE status IN ('QUEUED', 'PROCESSING', 'PENDING')")
    suspend fun failAllNonTerminal(reason: String)

    /**
     * TASK-390: column-scoped interim write. The previous read-modify-write
     * (getByTaskId + whole-row update) could copy a stale status back over a row
     * that a concurrent failNonTerminal/failAllNonTerminal had just closed,
     * resurrecting it as a ghost in-flight row with the error message wiped.
     * Scoping the UPDATE to these columns makes status resurrection impossible.
     */
    @Query("UPDATE logs SET result = :result, isPartial = :isPartial WHERE taskId = :taskId")
    suspend fun updateInterimResult(taskId: String, result: String, isPartial: Boolean)

    /**
     * TASK-568: decoded audio seconds at the failure point, column-scoped
     * like [updateInterimResult]. Written by the streaming catches when a
     * run dies mid-stream, so the ERROR row can say decoded-of-total; the
     * success path keeps durationMs as processing time and never calls this.
     */
    @Query("UPDATE logs SET durationMs = :durationMs WHERE taskId = :taskId")
    suspend fun updateFailureDecodedMs(taskId: String, durationMs: Long)

    /** TASK-570: structured failure diagnostics, column-scoped (JSON from
     *  [FailureContextJson]); written once at failure time. */
    @Query("UPDATE logs SET failureContext = :json WHERE taskId = :taskId")
    suspend fun updateFailureContext(taskId: String, json: String?)

    /** Same TASK-390 contract as [updateInterimResult], for the duration column. */
    @Query("UPDATE logs SET audioDurationSeconds = :seconds WHERE taskId = :taskId")
    suspend fun updateAudioDuration(taskId: String, seconds: Double)

    /** TASK-336: rows closed by the cold-start sweep = the process died mid-transcription (OEM background kill). */
    @Query("SELECT COUNT(*) FROM logs WHERE errorMessage LIKE 'Interrupted by app restart%' AND timestamp > :since")
    suspend fun countInterruptedSince(since: Long): Int
}
