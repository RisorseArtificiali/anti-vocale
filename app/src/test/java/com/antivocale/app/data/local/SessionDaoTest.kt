package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK-1037 (GH #190) phase 2: the session store's DAO contract. The
 * contiguous-prefix offset is the resume machinery's single source (text
 * and offset cannot drift apart), so its edge cases are pinned here
 * before any runtime writer exists (phase 3).
 */
@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(taskId: String, state: String = TranscriptionSessionEntity.STATE_ACTIVE) =
        TranscriptionSessionEntity(
            taskId = taskId,
            filePath = "/data/shared_audio/note.m4a",
            fileSizeBytes = 1024L,
            fileMtimeMs = 42L,
            backendId = "parakeet",
            modelPath = "/models/parakeet-tdt",
            languagePin = "auto",
            source = "SHARE",
            sourcePackage = "com.whatsapp",
            senderName = "Hermes",
            state = state,
            createdAtMs = 100L,
            updatedAtMs = 100L,
        )

    private fun chunk(taskId: String, start: Long, end: Long, text: String = "text@$start") =
        SessionChunkEntity(taskId = taskId, startSample = start, endSample = end, text = text)

    @Test
    fun `upsertSession inserts and a re-upsert replaces the row`() = runBlocking {
        dao.upsertSession(session("t1"))
        assertEquals(TranscriptionSessionEntity.STATE_ACTIVE, dao.getSession("t1")!!.state)

        dao.upsertSession(session("t1", state = TranscriptionSessionEntity.STATE_PAUSED_USER))
        val replaced = dao.getSession("t1")!!
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, replaced.state)
    }

    @Test
    fun `getSession returns null for an unknown task`() = runBlocking {
        assertNull(dao.getSession("nope"))
    }

    @Test
    fun `getActiveSessions returns ACTIVE and COMPLETING, never paused rows`() = runBlocking {
        dao.upsertSession(session("live", TranscriptionSessionEntity.STATE_ACTIVE))
        dao.upsertSession(session("finishing", TranscriptionSessionEntity.STATE_COMPLETING))
        dao.upsertSession(session("userParked", TranscriptionSessionEntity.STATE_PAUSED_USER))
        dao.upsertSession(session("systemParked", TranscriptionSessionEntity.STATE_PAUSED_SYSTEM))

        val ids = dao.getActiveSessions().map { it.taskId }.toSet()
        assertEquals(setOf("live", "finishing"), ids)
    }

    @Test
    fun `getChunks orders by startSample ascending regardless of insert order`() = runBlocking {
        dao.insertChunk(chunk("t1", 300, 400))
        dao.insertChunk(chunk("t1", 0, 100))
        dao.insertChunk(chunk("t1", 100, 300))

        assertEquals(listOf(0L, 100L, 300L), dao.getChunks("t1").map { it.startSample })
    }

    @Test
    fun `contiguousPrefixEnd walks the chain and stops at the gap`() = runBlocking {
        dao.upsertSession(session("t1"))
        dao.insertChunk(chunk("t1", 250, 400))
        dao.insertChunk(chunk("t1", 0, 100))
        dao.insertChunk(chunk("t1", 100, 250))
        // Gap: 400 -> 500.
        dao.insertChunk(chunk("t1", 500, 600))

        assertEquals(400L, dao.contiguousPrefixEnd("t1"))
    }

    @Test
    fun `contiguousPrefixEnd returns the single chunk end when one chunk is anchored at zero`() = runBlocking {
        dao.insertChunk(chunk("t1", 0, 160_000))
        assertEquals(160_000L, dao.contiguousPrefixEnd("t1"))
    }

    @Test
    fun `contiguousPrefixEnd returns 0 with no chunks and when nothing starts at zero`() = runBlocking {
        assertEquals(0L, dao.contiguousPrefixEnd("t1"))

        // Rows that do not cover the file from the start must not yield an
        // offset that silently skips uncovered samples: decode from zero.
        dao.insertChunk(chunk("t1", 500, 600))
        dao.insertChunk(chunk("t1", 600, 700))
        assertEquals(0L, dao.contiguousPrefixEnd("t1"))
    }

    @Test
    fun `deleteSession removes the row and cascades to its chunks only`() = runBlocking {
        dao.upsertSession(session("t1"))
        dao.insertChunk(chunk("t1", 0, 100))
        dao.insertChunk(chunk("t1", 100, 200))
        dao.upsertSession(session("t2"))
        dao.insertChunk(chunk("t2", 0, 100))

        dao.deleteSession("t1")

        assertNull(dao.getSession("t1"))
        assertTrue(dao.getChunks("t1").isEmpty())
        assertEquals(1, dao.getChunks("t2").size)
        assertEquals(100L, dao.contiguousPrefixEnd("t2"))
    }
}
