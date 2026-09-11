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
        // "PENDING" is a legacy row value; the mapper resolves it to PROCESSING
        assertEquals(LogEntry.Status.PROCESSING, entry.status)
    }

    // ── toLogEntry: legacy PENDING rows (pre-QUEUED/PROCESSING schema) ──

    @Test
    fun `toLogEntry maps legacy PENDING status to PROCESSING`() {
        val entity = LogEntity(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = "AUDIO",
            status = "PENDING"
        )

        val entry = entity.toLogEntry()

        // Rows written before the QUEUED/PROCESSING split said "PENDING" for
        // both meanings; PROCESSING is the safe reading (most such rows are
        // mid-flight at upgrade time, and terminal states clean them up).
        assertEquals(LogEntry.Status.PROCESSING, entry.status)
    }

    @Test
    fun `toLogEntry maps QUEUED status`() {
        val entity = LogEntity(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = "AUDIO",
            status = "QUEUED"
        )

        assertEquals(LogEntry.Status.QUEUED, entity.toLogEntry().status)
    }

    // ── modelName round-trip (GH #45) ───────────────────────────────

    @Test
    fun `modelName survives both mapping directions`() {
        val entry = entityWithStatus("SUCCESS").let { it.toLogEntry().copy(modelName = "Whisper Turbo") }
        assertEquals("Whisper Turbo", entry.toEntity().toLogEntry().modelName)
        assertNull(entityWithStatus("SUCCESS").toLogEntry().modelName)
    }

    private fun entityWithStatus(status: String) = LogEntity(
        id = "test-id", timestamp = 1000L, taskId = "task-1",
        type = "AUDIO", status = status,
    )

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


    @Test
    fun `summarySkipReason survives both mapping directions`() {
        val entity = LogEntity(
            id = "test-id",
            timestamp = 1000L,
            taskId = "task-1",
            type = "AUDIO",
            status = "SUCCESS",
            summarySkipReason = "guards"
        )
        assertEquals("guards", entity.toLogEntry().summarySkipReason)
        assertEquals("guards", entity.toLogEntry().toEntity().summarySkipReason)
    }
}
