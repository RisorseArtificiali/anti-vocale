package com.antivocale.app.data

import com.antivocale.app.data.local.LogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-844: the history payload codec. Pure JVM round-trip over a
 * full-fidelity entity (every column set to a non-default, distinguishable
 * value so a dropped column reads as its default and FAILS the assert,
 * the projection-completeness trap the lean list queries document).
 */
class BackupHistoryCodecTest {

    private val fullRow = LogEntity(
        id = "row-1",
        timestamp = 1_790_000_000_000L,
        taskId = "task-1",
        type = "audio",
        status = "SUCCESS",
        prompt = "prompt text",
        result = "transcript text",
        errorMessage = "boom",
        durationMs = 1234L,
        filePath = "/data/voicenote.opus",
        audioDurationSeconds = 7.5,
        sourcePackageName = "com.whatsapp",
        senderName = "Hermes IBM",
        isPartial = true,
        failedChunkCount = 2,
        modelName = "Parakeet TDT int8",
        rawTranscript = "raw text",
        summary = "summary text",
        summarySkipReason = "context_limit",
        segments = """[{"startMs":0,"endMs":100,"text":"ciao"}]""",
        failureContext = """{"error":"oom"}""",
        processingContext = """{"decode":"ffmpeg"}""",
        firstPassTranscript = "first pass text",
        detectedLanguage = "it",
        languagePin = "auto",
        savedFolderName = "Transcripts",
        savedFileName = "t.txt",
        saveFailureReason = "FAIL_VERIFY_EMPTY",
        // TASK-842's column rides the full-fidelity rule (non-default, so a
        // dropped key fails the round-trip assert).
        contentHash = "deadbeef00",
    )

    @Test
    fun `a full-fidelity row round-trips every column`() {
        val json = BackupHistoryCodec.rowToJson(fullRow)
        val decoded = BackupHistoryCodec.rowFromJson(json)
        assertEquals(fullRow, decoded)
    }

    @Test
    fun `the NDJSON payload round-trips through read`() {
        val lines = listOf(fullRow, fullRow.copy(id = "row-2", timestamp = 2L))
            .joinToString("\n") { BackupHistoryCodec.rowToJson(it).toString() }
            .toByteArray()
        val decoded = BackupHistoryCodec.readHistoryEntry(lines)
        assertEquals(2, decoded.size)
        assertEquals(fullRow, decoded.first())
        assertEquals("row-2", decoded.last().id)
    }

    @Test
    fun `a malformed line costs one row, not the archive`() {
        val good = BackupHistoryCodec.rowToJson(fullRow).toString()
        val payload = listOf(good, "{ not json", good).joinToString("\n")
        assertEquals(2, BackupHistoryCodec.readHistoryEntry(payload.toByteArray()).size)
    }

    @Test
    fun `an empty payload reads as empty, a null column stays null`() {
        assertTrue(BackupHistoryCodec.readHistoryEntry(ByteArray(0)).isEmpty())
        val minimal = """{"id":"m","timestamp":5}"""
        val row = BackupHistoryCodec.readHistoryEntry(minimal.toByteArray()).single()
        assertEquals("m", row.id)
        assertNull("a missing key reads the entity's own default (null), never a silent value",
            row.errorMessage)
        assertEquals("", row.result)
    }

    @Test
    fun `the chunked import flushes bounded batches and counts drops`() = kotlinx.coroutines.runBlocking {
        // The import path's memory contract: rows reach the emitter in
        // chunkSize batches, never as one list; drops count, not abort.
        val good = BackupHistoryCodec.rowToJson(fullRow).toString()
        val payload = listOf(good, good, "{ not json", good, good)
            .joinToString("\n").toByteArray()
        val batches = mutableListOf<Int>()
        val dropped = BackupHistoryCodec.readHistoryEntryChunked(
            payload, chunkSize = 2) { chunk -> batches.add(chunk.size) }
        assertEquals(listOf(2, 2), batches)
        assertEquals(1, dropped)
    }
}
