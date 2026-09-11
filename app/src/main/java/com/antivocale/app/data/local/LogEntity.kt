package com.antivocale.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.antivocale.app.ui.viewmodel.LogEntry

@Entity(
    tableName = "logs",
    indices = [Index("timestamp")]
)
data class LogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val taskId: String,
    val type: String,
    val status: String,
    val prompt: String = "",
    val result: String = "",
    val errorMessage: String? = null,
    val durationMs: Long = 0,
    val filePath: String? = null,
    val audioDurationSeconds: Double = 0.0,
    val sourcePackageName: String? = null,
    /** True when transcription completed but one or more audio chunks were skipped (e.g. low-RAM OOM). */
    val isPartial: Boolean = false,
    /** Number of audio chunks that failed (only meaningful when isPartial == true). */
    val failedChunkCount: Int = 0,
    /** Display name of the model that produced this transcription (GH #45; null on pre-v4 rows). */
    val modelName: String? = null,
    /** TASK-276 AC3: the raw ASR text before the punctuation pass, kept when the
     *  pass changed the words' presentation (null when it never fired or made
     *  no change; null on pre-v5 rows). */
    val rawTranscript: String? = null,
    /** TASK-121.4: the AI summary of a long transcript, attached as metadata
     *  (null when the pass never fired or degraded; null on pre-v6 rows). */
    val summary: String? = null,
    /** TASK-494: stable token for why an attended summary attempt produced
     *  none: guards, context limit, no model, or generation failure. Null on
     *  pre-v7 rows, on every clean skip (toggle, short transcript), and on
     *  success. */
    val summarySkipReason: String? = null,
)

fun LogEntity.toLogEntry(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    taskId = taskId,
    type = LogEntry.Type.valueOf(type),
    // Explicit legacy alias: rows written before the QUEUED/PROCESSING split (GH #51)
    // stored "PENDING" for both meanings. Anything else unknown fails loudly rather
    // than silently rendering as in-progress forever.
    status = if (status == "PENDING") LogEntry.Status.PROCESSING else LogEntry.Status.valueOf(status),
    prompt = prompt,
    result = result,
    errorMessage = errorMessage,
    durationMs = durationMs,
    filePath = filePath,
    audioDurationSeconds = audioDurationSeconds,
    sourcePackageName = sourcePackageName,
    isPartial = isPartial,
    failedChunkCount = failedChunkCount,
    modelName = modelName,
    rawTranscript = rawTranscript,
    summary = summary,
    summarySkipReason = summarySkipReason
)

fun LogEntry.toEntity(): LogEntity = LogEntity(
    id = id,
    timestamp = timestamp,
    taskId = taskId,
    type = type.name,
    status = status.name,
    prompt = prompt,
    result = result,
    errorMessage = errorMessage,
    durationMs = durationMs,
    filePath = filePath,
    audioDurationSeconds = audioDurationSeconds,
    sourcePackageName = sourcePackageName,
    isPartial = isPartial,
    failedChunkCount = failedChunkCount,
    modelName = modelName,
    rawTranscript = rawTranscript,
    summary = summary,
    summarySkipReason = summarySkipReason
)
