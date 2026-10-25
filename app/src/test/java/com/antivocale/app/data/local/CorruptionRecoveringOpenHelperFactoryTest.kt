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
        assertEquals("exactly one recovery retry", 2, opens)
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
        io.mockk.verify(atLeast = 1) { innerHelper.close() }
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

        assertEquals(2, opens)
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
    fun `clean access is a pure passthrough`() {
        every { innerHelper.writableDatabase } returns freshDb
        val before = HistoryRecoveryLedger.recoveredAt.value

        helper().writableDatabase // passthrough: no exception, no ledger change
        assertEquals("no new recovery recorded", before, HistoryRecoveryLedger.recoveredAt.value)
    }
}
