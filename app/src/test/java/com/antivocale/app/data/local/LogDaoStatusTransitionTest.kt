package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GH #51: the queue-state lifecycle is DAO-level SQL (single source for the
 * non-terminal set, including the legacy "PENDING" spelling). These tests pin
 * the transitions the orchestrator/service rely on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LogDaoStatusTransitionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.logDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(taskId: String, status: String): LogEntity {
        val entity = LogEntity(
            id = "id-$taskId", timestamp = 1L, taskId = taskId,
            type = "AUDIO", status = status,
        )
        dao.insert(entity)
        return entity
    }

    @Test
    fun `promoteToProcessing promotes QUEUED and legacy PENDING, leaves terminal rows alone`() = runBlocking {
        insert("queued", "QUEUED")
        insert("legacy", "PENDING")
        insert("done", "SUCCESS")

        dao.promoteToProcessing("queued")
        dao.promoteToProcessing("legacy")
        dao.promoteToProcessing("done")

        assertEquals("PROCESSING", dao.getByTaskId("queued")?.status)
        assertEquals("PROCESSING", dao.getByTaskId("legacy")?.status)
        assertEquals("SUCCESS", dao.getByTaskId("done")?.status)
    }

    @Test
    fun `failNonTerminalForTaskIds fails only non-terminal rows of the given tasks`() = runBlocking {
        insert("q1", "QUEUED")
        insert("q2", "PROCESSING")
        insert("done", "SUCCESS")
        insert("other", "QUEUED")

        dao.failNonTerminalForTaskIds(listOf("q1", "q2", "done"), "Cancelled")

        assertEquals("ERROR", dao.getByTaskId("q1")?.status)
        assertEquals("Cancelled", dao.getByTaskId("q1")?.errorMessage)
        assertEquals("ERROR", dao.getByTaskId("q2")?.status)
        // A terminal row and a non-listed task must be untouched
        assertEquals("SUCCESS", dao.getByTaskId("done")?.status)
        assertEquals("QUEUED", dao.getByTaskId("other")?.status)
    }

    @Test
    fun `failAllNonTerminal closes every non-terminal row including legacy PENDING`() = runBlocking {
        insert("sweep-queued", "QUEUED")
        insert("sweep-proc", "PROCESSING")
        insert("sweep-legacy", "PENDING")
        insert("sweep-done", "SUCCESS")

        dao.failAllNonTerminal("Interrupted by app restart")

        assertEquals("ERROR", dao.getByTaskId("sweep-queued")?.status)
        assertEquals("ERROR", dao.getByTaskId("sweep-proc")?.status)
        assertEquals("ERROR", dao.getByTaskId("sweep-legacy")?.status)
        assertEquals("SUCCESS", dao.getByTaskId("sweep-done")?.status)
    }

    @Test
    fun `failNonTerminal closes a single non-terminal task`() = runBlocking {
        insert("q1", "QUEUED")
        insert("done", "SUCCESS")

        dao.failNonTerminal("q1", "Interrupted", durationMs = 42)

        val failed = dao.getByTaskId("q1")!!
        assertEquals("ERROR", failed.status)
        assertEquals("Interrupted", failed.errorMessage)
        assertEquals(42L, failed.durationMs)
        assertEquals("SUCCESS", dao.getByTaskId("done")?.status)
    }

    @Test
    fun `interim column updates cannot resurrect a closed row`() = runBlocking {
        // TASK-390: the column-scoped updates cannot touch status at all.
        val taskId = "resurrect-pin"
        insert(taskId, "PROCESSING")
        dao.failNonTerminal(taskId, "Cancelled", 0L)
        dao.updateInterimResult(taskId, "partial text", isPartial = true)
        dao.updateAudioDuration(taskId, 12.5)
        val row = dao.getByTaskId(taskId)!!
        assertEquals("ERROR", row.status)
        assertEquals("Cancelled", row.errorMessage)
        assertEquals("partial text", row.result)
        assertEquals(12.5, row.audioDurationSeconds, 0.001)
    }

    @Test
    fun `getNonTerminal returns the same set the sweep closes`() = runBlocking {
        insert("queued", "QUEUED")
        insert("proc", "PROCESSING")
        insert("legacy", "PENDING")
        insert("done", "SUCCESS")
        insert("failed", "ERROR")

        val ids = dao.getNonTerminal().map { it.taskId }.toSet()
        assertEquals(setOf("queued", "proc", "legacy"), ids)
    }

    @Test
    fun `markSuspendedBySystem closes a non-terminal row with the typed marker`() = runBlocking {
        insert("suspended", "PROCESSING")
        insert("done", "SUCCESS")

        dao.markSuspendedBySystem(
            taskId = "suspended",
            errorMessage = "localized suspension message",
            failureContext = """{"errorClass":"SystemSuspended","suspendedMs":300000}""")

        val row = dao.getByTaskId("suspended")!!
        assertEquals("ERROR", row.status)
        assertEquals("localized suspension message", row.errorMessage)
        assertEquals("""{"errorClass":"SystemSuspended","suspendedMs":300000}""", row.failureContext)
        // Terminal rows are untouchable, like every other close.
        assertEquals("SUCCESS", dao.getByTaskId("done")?.status)
    }

    @Test
    fun `suspended rows count as interruptions for the battery-exemption card`() = runBlocking {
        // TASK-684: the localized errorMessage cannot be SQL-matched; the
        // failureContext marker is what the count sees. Fresh per-test DB, so
        // the since filter is degenerate (0) and cannot see other rows.
        insert("suspended", "PROCESSING")
        dao.markSuspendedBySystem(
            taskId = "suspended",
            errorMessage = "messaggio localizzato",
            failureContext = """{"errorClass":"SystemSuspended","suspendedMs":300000}""")

        assertEquals(1, dao.countInterruptedSince(since = 0L))
    }

    @Test
    fun `generic interrupted rows still count for the card`() = runBlocking {
        insert("killed", "PROCESSING")
        dao.failNonTerminal("killed", "Interrupted by app restart", 0L)

        assertEquals(1, dao.countInterruptedSince(since = 0L))
    }
}
