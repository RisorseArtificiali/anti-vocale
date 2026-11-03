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
 * RENAMED aside when the filesystem cooperates (TASK-892: DELETED when
 * the rename fails, so the launch loop cannot survive; the ledger flags
 * which happened), a fresh database is returned, the ledger records the
 * event exactly once, a second corruption during the retry rethrows (a
 * dying disk must not become a delete-everything loop), and the clean
 * path is a pure passthrough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptionRecoveringOpenHelperFactoryTest {

    private val dbName = "anti_vocale_database"

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var roboContext: Context
    private lateinit var databasesDir: File
    private val freshDb = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val healthyCursor = mockk<android.database.Cursor>()
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
        every { innerHelper.databaseName } returns dbName
        every { freshDb.query(any<String>()) } returns healthyCursor
        HistoryRecoveryLedger.record(-1, preservedAside = true) // sentinel distinguishing record() from never-called
    }

    private fun helper(): CorruptionRecoveringOpenHelperFactory.RecoveringHelper =
        CorruptionRecoveringOpenHelperFactory(innerFactory)
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(roboContext)
                    .name(dbName)
                    .callback(mockk(relaxed = true))
                    .build(),
            ) as CorruptionRecoveringOpenHelperFactory.RecoveringHelper

    /**
     * The common failing-first-access arrangement: the first
     * readableDatabase/writableDatabase call throws the corruption, the
     * reopened one returns [freshDb].
     */
    private fun arrangeFirstAccessFails(write: Boolean) {
        var opens = 0
        val stub = if (write) every { innerHelper.writableDatabase }
        else every { innerHelper.readableDatabase }
        stub answers {
            opens += 1
            if (opens == 1) throw SQLiteDiskIOException("disk I/O error (code 522)")
            freshDb
        }
    }

    private fun writeDamagedFiles() {
        File(databasesDir, dbName).writeText("damaged-main")
        File(databasesDir, dbName + "-wal").writeText("damaged-wal")
        File(databasesDir, dbName + "-shm").writeText("damaged-shm")
        File(databasesDir, dbName + "-journal").writeText("damaged-journal")
    }

    @Test
    fun `disk IO corruption renames files aside, retries fresh, records the ledger`() {
        writeDamagedFiles()
        arrangeFirstAccessFails(write = true)

        val result = helper().writableDatabase

        val inner = unwrappedDb(result)
        assertSame("the retry's fresh database is returned", freshDb, inner)
        // One recovery pass: exactly one close (the wrapper resolves the
        // helper per call, so open counts are no longer the retry count).
        verify(exactly = 1) { innerHelper.close() }
        val renamed = databasesDir.listFiles { _, name -> name.contains(dbName + DB_ASIDE_INFIX) }!!
        assertEquals("all four damaged files preserved aside", 4, renamed.size)
        assertTrue("the ORIGINAL damaged files are gone (moved, not copied)",
            !File(databasesDir, dbName).exists() &&
                !File(databasesDir, dbName + "-wal").exists() &&
                !File(databasesDir, dbName + "-shm").exists())
        assertTrue("the damaged CONTENT survives in the renamed main file",
            renamed.any { it.readText() == "damaged-main" })
        org.junit.Assert.assertNotEquals("the ledger recorded the recovery (not the setUp sentinel)",
            -1L, HistoryRecoveryLedger.lastRecovery.value?.stamp)
        assertTrue("a successful rename-aside records preservedAside",
            HistoryRecoveryLedger.lastRecovery.value?.preservedAside == true)
    }

    @Test
    fun `a failed rename deletes the damaged files and records the loss`() {
        // TASK-892: on a dying disk the rename can fail; leaving the file
        // in place would reopen the same corruption at EVERY launch (the
        // permanent crash loop this class exists to kill), so the recovery
        // falls back to delete and the ledger must say the bytes were NOT
        // preserved (the banner's wording depends on it).
        writeDamagedFiles()
        arrangeFirstAccessFails(write = false)
        val h = helper()
        h.renameAside = { _, _ -> false } // the dying-disk seam

        val result = h.readableDatabase

        assertSame("the recovery still delivers the fresh database", freshDb, unwrappedDb(result))
        assertEquals("no aside files were minted (rename failed)",
            0, databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size)
        assertTrue("the damaged files are GONE (delete fallback)",
            !File(databasesDir, dbName).exists() &&
                !File(databasesDir, dbName + "-wal").exists())
        assertEquals("the ledger recorded the recovery attempt as not preserved",
            false, HistoryRecoveryLedger.lastRecovery.value?.preservedAside)
    }

    @Test
    fun `aside sets are capped at the newest two`() {
        // TASK-892: repeated recoveries must not accumulate .corrupt sets
        // forever on a sick disk; the oldest beyond two are deleted. Three
        // pre-seeded sets plus the one this recovery mints: the two oldest
        // go, the newest pre-seeded set and the fresh one stay.
        writeDamagedFiles()
        listOf(111L, 222L, 333L).forEach { stamp ->
            File(databasesDir, dbName + DB_ASIDE_INFIX + stamp).writeText("old-$stamp")
            File(databasesDir, dbName + DB_ASIDE_INFIX + stamp + "-wal").writeText("old-wal")
        }
        arrangeFirstAccessFails(write = false)

        helper().readableDatabase

        val stamps = databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!
            .mapNotNull { Regex("corrupt-(\\d+)").find(it.name)?.groupValues?.get(1) }
            .toSet()
        assertEquals("exactly two aside sets survive", 2, stamps.size)
        assertTrue("the two oldest pre-seeded sets were pruned",
            "111" !in stamps && "222" !in stamps)
        assertTrue("the newest pre-seeded set and the fresh one survive",
            "333" in stamps)
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
        assertTrue(databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size == 4)
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
        assertTrue(databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size == 4)
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
    fun `a query-path recovery after an access recovery still resolves live`() {
        // TASK-890 review round 2: the access-recovery wrapper must keep
        // the LIVE resolver. A second corruption on the SAME wrapper (the
        // query path) re-swaps the database underneath it; a wrapper
        // pinned to the first recovery's instance would replay on the
        // closed handle and crash with IllegalStateException.
        val db2 = mockk<SupportSQLiteDatabase>()
        var db2Queries = 0
        every { db2.query(any<String>()) } answers {
            db2Queries += 1
            if (db2Queries == 1) throw SQLiteDiskIOException("transient blip")
            throw IllegalStateException("closed")
        }
        val db3 = mockk<SupportSQLiteDatabase>(relaxed = true)
        var stage = 0
        every { innerHelper.readableDatabase } answers {
            when (stage) {
                0 -> throw SQLiteDatabaseCorruptException("malformed")
                1 -> db2
                else -> db3
            }
        }
        every { innerHelper.close() } answers { stage += 1 }

        val db = helper().readableDatabase // access recovery -> db2
        db.query("SELECT 1") // query recovery -> db3
        db.query("SELECT 2") // must land on db3, not the closed db2

        verify(exactly = 2) { db3.query(any<String>()) }
    }

    @Test
    fun `an idempotent recovery attempt does not clobber the ledger notice`() {
        // The record fires only on attempts that MOVED something: a
        // racing thread finding nothing to rename must not overwrite the
        // first recovery's notice with a preservedAside=false re-surface
        // of the same event.
        writeDamagedFiles()
        arrangeFirstAccessFails(write = false)
        val h = helper()
        h.renameAside = { _, _ -> false } // the dying-disk seam

        h.readableDatabase // recovery: delete fallback, recorded unpreserved
        val after = HistoryRecoveryLedger.lastRecovery.value
        assertEquals(false, after?.preservedAside)

        h.recoverAfterQueryCorruption(SQLiteDiskIOException("idempotent"))

        assertSame("the no-op attempt left the notice alone", after,
            HistoryRecoveryLedger.lastRecovery.value)
    }

    @Test
    fun `transient query IOERR recovers by plain reopen without renaming anything`() {
        // TASK-896 first tier: one IOERR on the guarded query, then a
        // healthy database. The plain reopen (close, no rename, no
        // ledger) clears it; a rename here would have destroyed the
        // visible history of a perfectly healthy database.
        writeDamagedFiles()
        val damagedDb = mockk<SupportSQLiteDatabase>()
        var damagedQueries = 0
        every { damagedDb.query(any<String>()) } answers {
            damagedQueries += 1
            if (damagedQueries == 1) throw SQLiteDiskIOException("disk I/O error (WAL checkpoint blip)")
            healthyCursor
        }
        var closed = false
        every { innerHelper.readableDatabase } answers { if (closed) freshDb else damagedDb }
        every { innerHelper.close() } answers { closed = true }

        val db = helper().readableDatabase
        val cursor = db.query("SELECT * FROM logs")

        assertSame("the replay succeeded after the plain reopen", healthyCursor, cursor)
        verify(exactly = 1) { damagedDb.query(any<String>()) }
        verify(exactly = 1) { freshDb.query(any<String>()) }
        assertTrue("the damaged files are untouched on disk",
            File(databasesDir, dbName).readText() == "damaged-main")
        assertEquals("no aside set was minted",
            0, databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size)
        assertEquals("the ledger stayed at the setUp sentinel (no recovery event)",
            -1L, HistoryRecoveryLedger.lastRecovery.value?.stamp)
    }

    @Test
    fun `corruption on both query attempts escalates to the rename-aside tier`() {
        // The second corruption-class throw (the replay after the plain
        // reopen) fires the existing rename-aside recovery exactly as
        // shipped, then the same query replays on the fresh database.
        writeDamagedFiles()
        val damagedDb = mockk<SupportSQLiteDatabase>()
        every { damagedDb.query(any<String>()) } throws
            SQLiteDatabaseCorruptException("database disk image is malformed")
        // The plain reopen lands on a database that corrupts again: the
        // replay throws a second corruption class, which escalates.
        val stillCorruptDb = mockk<SupportSQLiteDatabase>()
        every { stillCorruptDb.query(any<String>()) } throws
            SQLiteDatabaseCorruptException("still malformed after reopen")
        var stage = 0
        every { innerHelper.close() } answers { stage += 1 }
        every { innerHelper.readableDatabase } answers {
            when (stage) {
                0 -> damagedDb
                1 -> stillCorruptDb
                else -> freshDb
            }
        }

        val db = helper().readableDatabase
        val cursor = db.query("SELECT * FROM logs")

        assertSame("the rename-tier replay succeeded", healthyCursor, cursor)
        verify(exactly = 1) { stillCorruptDb.query(any<String>()) }
        assertEquals("all four damaged files preserved aside",
            4, databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size)
        assertTrue("the ledger recorded the recovery",
            HistoryRecoveryLedger.lastRecovery.value?.preservedAside == true)
    }

    @Test
    fun `a 522 at plain reopen escalates to the rename-aside tier`() {
        // The Oct-18 truncated-file class: the plain reopen throws the
        // same 522 on the same bytes, and the ladder must still fall
        // through to the rename and deliver a fresh database. Keyed on
        // close: the first open "succeeds" onto the truncated handle,
        // the plain tier's reopened open hits the damaged bytes and
        // throws, and only after the rename-aside recovery has moved
        // the files does the open succeed onto freshDb.
        writeDamagedFiles()
        val truncatedDb = mockk<SupportSQLiteDatabase>()
        every { truncatedDb.query(any<String>()) } throws
            SQLiteDiskIOException("short read (522)")
        var closed = false
        every { innerHelper.close() } answers { closed = true }
        var renamedAside = false
        val h = helper()
        h.renameAside = { from, to ->
            val moved = from.renameTo(to)
            if (moved) renamedAside = true
            moved
        }
        every { innerHelper.readableDatabase } answers {
            when {
                renamedAside -> freshDb
                closed -> throw SQLiteDiskIOException("short read at open (522)")
                else -> truncatedDb
            }
        }

        val db = h.readableDatabase
        val cursor = db.query("SELECT * FROM logs")

        assertSame("the rename-tier replay succeeded", healthyCursor, cursor)
        assertEquals("the truncated file set was renamed aside",
            4, databasesDir.listFiles { _, n -> n.contains(dbName + DB_ASIDE_INFIX) }!!.size)
        assertTrue("the ledger recorded the recovery",
            HistoryRecoveryLedger.lastRecovery.value?.preservedAside == true)
    }

    @Test
    fun `clean access is a pure passthrough`() {
        every { innerHelper.writableDatabase } returns freshDb
        val before = HistoryRecoveryLedger.lastRecovery.value

        helper().writableDatabase // passthrough: no exception, no ledger change
        assertEquals("no new recovery recorded", before, HistoryRecoveryLedger.lastRecovery.value)
    }
}
