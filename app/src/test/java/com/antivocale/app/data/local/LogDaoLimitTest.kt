package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * TASK-340 Fix 2a: the UI log query must be bounded. LogsViewModel maps the whole
 * Flow result into new objects per emission, so an unbounded getAll kept the entire
 * (unbounded) history in the heap during progressive transcription. Pins the LIMIT
 * (500) and the recent-first ordering.
 */
@RunWith(AndroidJUnit4::class)
class LogDaoLimitTest {

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
        // Room's close() cancels its internal coroutine scope; on Robolectric that
        // surfaces as a benign JobCancellationException. Swallow it.
        try { db.close() } catch (_: Exception) {}
    }

    private fun entity(i: Int) = LogEntity(
        id = "id-$i",
        timestamp = 1_000_000L + i,
        taskId = "task-$i",
        type = "AUDIO",
        status = "SUCCESS",
        prompt = ""
    )

    @Test
    fun `getAll returns at most 500 rows`() = runBlocking {
        repeat(600) { dao.insert(entity(it)) }
        assertEquals(500, dao.getAll().first().size)
    }

    @Test
    fun `getAll is recent-first`() = runBlocking {
        repeat(3) { dao.insert(entity(it)) }
        val rows = dao.getAll().first()
        assertEquals(listOf("id-2", "id-1", "id-0"), rows.map { it.id })
    }
}
