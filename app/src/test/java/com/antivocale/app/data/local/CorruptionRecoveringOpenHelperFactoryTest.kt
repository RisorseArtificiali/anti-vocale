package com.antivocale.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-886: the History DB corruption-recovery path. A truncated database
 * file (SQLITE_IOERR_SHORT_READ at connection open) is a LAUNCH CRASH
 * LOOP - the History query runs at every app open. These tests pin the
 * recovery contract on a real temp filesystem: the damaged files are
 * RENAMED aside (preserved, never deleted), a fresh database is returned,
 * the ledger records the event exactly once, a second corruption during
 * the retry rethrows (a dying disk must not become a delete-everything
 * loop), and the clean path is a pure passthrough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptionRecoveringOpenHelperFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var roboContext: Context
    private lateinit var databasesDir: File
    private val freshDb = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val innerHelper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
    private val innerFactory = mockk<SupportSQLiteOpenHelper.Factory>()

    @Before
    fun setUp() {
        roboContext = mockk(relaxed = true)
        databasesDir = tmp.newFolder("databases")
        every { roboContext.getDatabasePath(any()) } answers {
            File(databasesDir, firstArg())
        }
        every { innerFactory.create(any()) } returns innerHelper
        every { innerHelper.databaseName } returns "anti_vocale_database"
        HistoryRecoveryLedger.record(-1) // sentinel distinguishing record() from never-called
    }

    private fun helper() = CorruptionRecoveringOpenHelperFactory(innerFactory)
        .create(
            SupportSQLiteOpenHelper.Configuration.builder(roboContext)
                .name("anti_vocale_database")
                .callback(mockk(relaxed = true))
                .build(),
        )

    private fun writeDamagedFiles() {
        File(databasesDir, "anti_vocale_database").writeText("damaged-main")
        File(databasesDir, "anti_vocale_database-wal").writeText("damaged-wal")
        File(databasesDir, "anti_vocale_database-shm").writeText("damaged-shm")
    }

    @Test
    fun `disk IO corruption renames files aside, retries fresh, records the ledger`() {
        writeDamagedFiles()
        var opens = 0
        every { innerHelper.writableDatabase } answers {
            opens += 1
            if (opens == 1) throw SQLiteDiskIOException("disk I/O error (code 522)")
            freshDb
        }

        val result = helper().writableDatabase

        val inner = unwrappedDb(result)
        assertSame("the retry's fresh database is returned", freshDb, inner)
        // One recovery pass: exactly one close (the wrapper resolves the
        // helper per call, so open counts are no longer the retry count).
        verify(exactly = 1) { innerHelper.close() }
        val renamed = databasesDir.listFiles { _, name -> name.contains(".corrupt-") }!!
        assertEquals("all three damaged files preserved aside", 3, renamed.size)
        assertTrue("the ORIGINAL damaged files are gone (moved, not copied)",
            !File(databasesDir, "anti_vocale_database").exists() &&
                !File(databasesDir, "anti_vocale_database-wal").exists() &&
                !File(databasesDir, "anti_vocale_database-shm").exists())
        assertTrue("the damaged CONTENT survives in the renamed main file",
            renamed.any { it.readText() == "damaged-main" })
        org.junit.Assert.assertNotEquals("the ledger recorded the recovery (not the setUp sentinel)",
            -1L, HistoryRecoveryLedger.recoveredAt.value)
    }

    @Test
    fun `corrupt exception takes the same recovery path`() {
        writeDamagedFiles()
        var opens = 0
        every { innerHelper.readableDatabase } answers {
            opens += 1
            if (opens == 1) throw SQLiteDatabaseCorruptException("database disk image is malformed")
            freshDb
        }

        val result = helper().readableDatabase

        verify(exactly = 1) { innerHelper.close() }
        assertTrue(databasesDir.listFiles { _, n -> n.contains(".corrupt-") }!!.size == 3)
    }

    @Test
    fun `a second corruption during the retry rethrows instead of looping`() {
        writeDamagedFiles()
        every { innerHelper.writableDatabase } throws SQLiteDiskIOException("still dying")

        var thrown: SQLiteDiskIOException? = null
        try {
            helper().writableDatabase
        } catch (e: SQLiteDiskIOException) {
            thrown = e
        }
        assertNotNull("the retry's failure surfaces to the caller", thrown)
        // The damaged files were still preserved by the one recovery pass.
        assertTrue(databasesDir.listFiles { _, n -> n.contains(".corrupt-") }!!.size == 3)
    }

    @Test
    fun `a second query after a mid-query recovery goes to the live database`() {
        // TASK-890: an in-flight block holds this wrapper across
        // statements, so the SECOND query arrives on the same object after
        // the recovery closed the damaged handle underneath. A wrapper
        // still pointing at it crashes with IllegalStateException (not a
        // corruption class: uncatchable at this layer, the mid-session
        // crash the fix exists to prevent). The inner helper models the
        // framework contract: readableDatabase returns the same instance
        // until close(), then the reopened fresh one.
        writeDamagedFiles()
        val damagedDb = mockk<SupportSQLiteDatabase>()
        var damagedQueries = 0
        every { damagedDb.query(any<String>()) } answers {
            damagedQueries += 1
            if (damagedQueries == 1) throw SQLiteDiskIOException("short read (522)")
            throw IllegalStateException("attempt to re-open an already-closed object")
        }
        var closed = false
        every { innerHelper.readableDatabase } answers { if (closed) freshDb else damagedDb }
        every { innerHelper.close() } answers { closed = true }

        val db = helper().readableDatabase
        db.query("SELECT * FROM logs") // first query: corrupted, recovered, replayed fresh
        db.query("SELECT * FROM logs") // the second query on the SAME wrapper

        verify(exactly = 2) { freshDb.query(any<String>()) }
    }

    @Test
    fun `clean access is a pure passthrough`() {
        every { innerHelper.writableDatabase } returns freshDb
        val before = HistoryRecoveryLedger.recoveredAt.value

        helper().writableDatabase // passthrough: no exception, no ledger change
        assertEquals("no new recovery recorded", before, HistoryRecoveryLedger.recoveredAt.value)
    }
}
