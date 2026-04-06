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
    val sourcePackageName: String? = null
)

fun LogEntity.toLogEntry(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    taskId = taskId,
    type = LogEntry.Type.valueOf(type),
    status = LogEntry.Status.valueOf(status),
    prompt = prompt,
    result = result,
    errorMessage = errorMessage,
    durationMs = durationMs,
    filePath = filePath,
    audioDurationSeconds = audioDurationSeconds,
    sourcePackageName = sourcePackageName
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
    sourcePackageName = sourcePackageName
)
