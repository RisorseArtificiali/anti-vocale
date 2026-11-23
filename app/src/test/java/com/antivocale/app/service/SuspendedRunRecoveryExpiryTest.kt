package com.antivocale.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.local.AppDatabase
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.SessionChunkEntity
import com.antivocale.app.data.local.TranscriptionSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-1037 phase 6: the paused-session expiry sweep's pins. The sweep is
 * the phase's only destructive path (row rewrite plus session and chunk
 * deletion), so both sides are behavior-pinned here on the same in-memory
 * Room the recovery harness uses: a paused session a month stale closes
 * as a kept-text ERROR partial with the localized reason, while a fresh
 * one survives untouched (the sweep never eats a session the user may
 * still resume).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SuspendedRunRecoveryExpiryTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: com.antivocale.app.data.local.SessionDao
    private lateinit var logDao: com.antivocale.app.data.local.LogDao

    private fun pausedSession(taskId: String, updatedAtMs: Long) = TranscriptionSessionEntity(
        taskId = taskId,
        filePath = "/data/session-$taskId.opus", fileSizeBytes = 10L, fileMtimeMs = 1L,
        backendId = "sherpa-onnx", modelPath = "/models/parakeet",
        source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_USER,
        totalOutputSamples = 9_600_000L,
        createdAtMs = updatedAtMs - 1000L, updatedAtMs = updatedAtMs)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.sessionDao()
        logDao = db.logDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun plantPausedRow(taskId: String, text: String) {
        logDao.insert(LogEntity(
            id = "row-$taskId", timestamp = 1L, taskId = taskId,
            type = "AUDIO", status = "PAUSED", filePath = "/data/session-$taskId.opus",
            result = text))
    }

    @Test
    fun `a month-stale paused session closes as kept-text partial, session and chunks go`() = runBlocking {
        val stale = "stale-task"
        val fresh = "fresh-task"
        val now = System.currentTimeMillis()
        plantPausedRow(stale, "transcribed so far")
        plantPausedRow(fresh, "still going")
        sessionDao.upsertSession(pausedSession(stale, updatedAtMs = now - 31L * 24 * 60 * 60 * 1000))
        sessionDao.upsertSession(pausedSession(fresh, updatedAtMs = now))
        sessionDao.insertChunk(SessionChunkEntity(
            taskId = stale, startSample = 0L, endSample = 9_600_000L, text = "transcribed so far"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        SuspendedRunRecovery.closeInterruptedRuns(
            context = context, logDao = logDao, sessionDao = sessionDao,
            wasOOMCrash = false, notifyGenericInterrupted = false)

        val closed = logDao.getByTaskId(stale)
        assertEquals("ERROR", closed?.status)
        assertEquals(true, closed?.isPartial)
        assertEquals("kept text survives the close", "transcribed so far", closed?.result)
        assertNull("the stale session row is gone", sessionDao.getSession(stale))
        assertEquals("no chunk rows survive the delete", 0, sessionDao.getChunks(stale).size)
        val surviving = logDao.getByTaskId(fresh)
        assertEquals("a fresh paused session is untouched", "PAUSED", surviving?.status)
        assertEquals("and so is its session row",
            TranscriptionSessionEntity.STATE_PAUSED_USER, sessionDao.getSession(fresh)?.state)
    }
}
