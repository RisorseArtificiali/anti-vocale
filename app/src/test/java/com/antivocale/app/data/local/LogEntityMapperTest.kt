package com.antivocale.app.data.local

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

class LogEntityMapperTest {

    // ── toLogEntry: sourcePackageName present ──────────────────────

    @Test
    fun `toLogEntry maps sourcePackageName when present`() {
        val entity = LogEntity(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = "AUDIO",
            status = "SUCCESS",
            sourcePackageName = "com.whatsapp"
        )

        val entry = entity.toLogEntry()

        assertEquals("com.whatsapp", entry.sourcePackageName)
        assertEquals(LogEntry.Type.AUDIO, entry.type)
        assertEquals(LogEntry.Status.SUCCESS, entry.status)
    }

    // ── toLogEntry: sourcePackageName null ─────────────────────────

    @Test
    fun `toLogEntry maps sourcePackageName null correctly`() {
        val entity = LogEntity(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = "TEXT",
            status = "PENDING",
            sourcePackageName = null
        )

        val entry = entity.toLogEntry()

        assertNull(entry.sourcePackageName)
        assertEquals(LogEntry.Type.TEXT, entry.type)
        assertEquals(LogEntry.Status.PENDING, entry.status)
    }

    // ── toEntity: sourcePackageName present ────────────────────────

    @Test
    fun `toEntity maps sourcePackageName when present`() {
        val entry = LogEntry(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = LogEntry.Type.AUDIO,
            status = LogEntry.Status.ERROR,
            sourcePackageName = "com.telegram.messenger"
        )

        val entity = entry.toEntity()

        assertEquals("com.telegram.messenger", entity.sourcePackageName)
        assertEquals("AUDIO", entity.type)
        assertEquals("ERROR", entity.status)
    }

    // ── toEntity: sourcePackageName null ───────────────────────────

    @Test
    fun `toEntity maps sourcePackageName null correctly`() {
        val entry = LogEntry(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = LogEntry.Type.TEXT,
            status = LogEntry.Status.SUCCESS,
            sourcePackageName = null
        )

        val entity = entry.toEntity()

        assertNull(entity.sourcePackageName)
    }

    // ── Round-trip: entity -> entry -> entity ──────────────────────

    @Test
    fun `round-trip preserves sourcePackageName`() {
        val original = LogEntity(
            id = "round-trip-id",
            timestamp = 9999L,
            taskId = "task-x",
            type = "AUDIO",
            status = "SUCCESS",
            prompt = "test prompt",
            result = "test result",
            errorMessage = null,
            durationMs = 500L,
            filePath = "/path/to/audio.ogg",
            audioDurationSeconds = 3.5,
            sourcePackageName = "com.whatsapp"
        )

        val result = original.toLogEntry().toEntity()

        assertEquals(original.id, result.id)
        assertEquals(original.timestamp, result.timestamp)
        assertEquals(original.taskId, result.taskId)
        assertEquals(original.type, result.type)
        assertEquals(original.status, result.status)
        assertEquals(original.prompt, result.prompt)
        assertEquals(original.result, result.result)
        assertEquals(original.errorMessage, result.errorMessage)
        assertEquals(original.durationMs, result.durationMs)
        assertEquals(original.filePath, result.filePath)
        assertEquals(original.audioDurationSeconds, result.audioDurationSeconds, 0.001)
        assertEquals(original.sourcePackageName, result.sourcePackageName)
    }
}
