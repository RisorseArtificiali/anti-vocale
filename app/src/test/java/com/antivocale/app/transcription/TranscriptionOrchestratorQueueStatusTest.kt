package com.antivocale.app.transcription

import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.toLogEntry
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Queue-state logging contract (GH #51): a request must be visible in the log
 * from the moment it enters the queue (QUEUED, inserted by logQueued), and be
 * promoted to PROCESSING by a DAO-level transition when work starts. The SQL
 * transitions themselves are pinned by LogDaoStatusTransitionTest.
 */
class TranscriptionOrchestratorQueueStatusTest {

    private fun orchestrator(logDao: LogDao): TranscriptionOrchestrator =
        TranscriptionOrchestrator(
            preferencesManager = mockk(relaxed = true),
            logDao = logDao,
            transcriptionCalibrator = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            audioPreprocessor = mockk<AudioPreprocessor>(),
            backendRegistry = staticRegistry(),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            externalModelStore = mockk(relaxed = true),
        )

    @Test
    fun `logQueued inserts a QUEUED entry with the request metadata`() = runTest {
        val logDao = mockk<LogDao>(relaxed = true)
        val orchestrator = orchestrator(logDao)

        orchestrator.logQueued(
            taskId = "task-1",
            requestType = "audio",
            prompt = "prompt",
            filePath = "/tmp/a.wav",
            sourcePackageName = "com.whatsapp",
        )

        val entitySlot = slot<com.antivocale.app.data.local.LogEntity>()
        coVerify { logDao.insert(capture(entitySlot)) }
        val inserted = entitySlot.captured.toLogEntry()
        assertEquals(com.antivocale.app.ui.viewmodel.LogEntry.Status.QUEUED, inserted.status)
        assertEquals("task-1", inserted.taskId)
        assertEquals(com.antivocale.app.ui.viewmodel.LogEntry.Type.AUDIO, inserted.type)
        assertEquals("/tmp/a.wav", inserted.filePath)
        assertEquals("com.whatsapp", inserted.sourcePackageName)
    }

    @Test
    fun `logQueued maps subtitles to AUDIO and everything else to TEXT`() = runTest {
        val logDao = mockk<LogDao>(relaxed = true)
        val orchestrator = orchestrator(logDao)

        orchestrator.logQueued(taskId = "t-sub", requestType = "subtitles")
        orchestrator.logQueued(taskId = "t-txt", requestType = "text")
        // TASK-677: a handed subtitle file is TEXT, not AUDIO: the row must
        // never offer re-transcribe (there is no audio to decode), which the
        // Type.AUDIO gate on that action would.
        orchestrator.logQueued(taskId = "t-imp", requestType = "subtitle_import")

        val inserts = mutableListOf<com.antivocale.app.data.local.LogEntity>()
        coVerify(exactly = 3) { logDao.insert(capture(inserts)) }
        assertEquals(
            listOf("AUDIO", "TEXT", "TEXT"),
            inserts.map { it.type },
        )
    }

    @Test
    fun `markProcessing delegates to the DAO promotion, never inserts or rewrites the row`() = runTest {
        val logDao = mockk<LogDao>(relaxed = true)
        val orchestrator = orchestrator(logDao)

        orchestrator.markProcessing("task-1")

        // Update-only by design: the single insert point is logQueued, so this
        // racing the enqueue write cannot duplicate the row, and an entry the
        // user deleted mid-flight stays deleted.
        coVerify(exactly = 1) { logDao.promoteToProcessing("task-1") }
        coVerify(exactly = 0) { logDao.insert(any()) }
        coVerify(exactly = 0) { logDao.update(any()) }
    }
}
