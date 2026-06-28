package com.antivocale.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Proves the v2 -> v3 logs migration (TASK-205) runs cleanly and passes Room's runtime
 * schema validation — the exact check that throws `IllegalStateException: Migration didn't
 * properly handle: LogEntity` when the migration output doesn't match the @Database expectation.
 *
 * The v2 schema is hand-crafted here because `exportSchema` was off at v2, so no v2 schema
 * asset exists. We then open the file with the production [AppDatabase] + its real migrations;
 * Room runs [AppDatabase.MIGRATION_2_3] and validates the result against the v3 entity.
 *
 * What this guards: that MIGRATION_2_3 actually runs, the seed row survives, and Room opens
 * the DB at v3 without throwing — catching broken migration SQL, a missing migration, or
 * entity/migration drift that Room enforces (e.g. a missing column).
 *
 * Calibrated (2026-06-28, after a real device crash): Room 2.7.1 tolerates a column DEFAULT
 * declared in the migration but absent from the entity (it only validates defaults the entity
 * explicitly declares via @ColumnInfo). An earlier revision added @ColumnInfo(defaultValue="0")
 * to LogEntity "for consistency" — that was WRONG: it changed Room's v3 identity hash WITHOUT a
 * version bump, so existing v3 installs (same version, different hash) crashed at startup with
 * "Room cannot verify the data integrity". Reverted. The 06-14 migration as written (entity with
 * Kotlin defaults, no @ColumnInfo) is correct; this test guards that it runs, the row survives,
 * and Room opens at v3.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        // Room's close() cancels its internal coroutine scope; on Robolectric that surfaces
        // as a benign JobCancellationException. Swallow it so cleanup can't mask the result.
        try { db?.close() } catch (_: Exception) {}
        db = null
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun migrate_2_to_3_preservesRowAndPassesSchemaValidation() {
        seedV2Database()

        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        // Opening + querying triggers MIGRATION_2_3 and Room's runtime schema validation.
        // If the migration SQL is broken or a column is missing, Room throws here.
        val row = runBlocking { db!!.logDao().getByTaskId("task-1") }

        assertNotNull("Seed row must survive the migration", row)
        assertEquals("task-1", row!!.taskId)
        assertEquals("SUCCESS", row.status)
        // Columns added by MIGRATION_2_3 default to 0/false for pre-existing rows.
        assertFalse("isPartial should default to false for a v2 row", row.isPartial)
        assertEquals(0, row.failedChunkCount)
    }

    /** A fresh v3 DB (no migration) must also be internally consistent with the entity. */
    @Test
    fun fresh_v3_database_isConsistent() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            db!!.logDao().insert(
                sampleLog(id = "fresh-1", taskId = "task-fresh", isPartial = true, failedChunkCount = 2)
            )
        }
        val row = runBlocking { db!!.logDao().getByTaskId("task-fresh") }

        assertNotNull(row)
        assertEquals(true, row!!.isPartial)
        assertEquals(2, row.failedChunkCount)
    }

    /**
     * Builds the pre-v3 schema directly via SQLite: the 12 columns LogEntity had before
     * isPartial/failedChunkCount were added, plus the timestamp index Room expects.
     */
    private fun seedV2Database() {
        val sql = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sql.execSQL(
            """
            CREATE TABLE logs (
                id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                taskId TEXT NOT NULL,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                prompt TEXT NOT NULL,
                result TEXT NOT NULL,
                errorMessage TEXT,
                durationMs INTEGER NOT NULL,
                filePath TEXT,
                audioDurationSeconds REAL NOT NULL,
                sourcePackageName TEXT,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        sql.execSQL("CREATE INDEX index_logs_timestamp ON logs(timestamp)")
        sql.execSQL(
            "INSERT INTO logs (id, timestamp, taskId, type, status, prompt, result, durationMs, audioDurationSeconds) " +
                "VALUES ('log-1', 1700000000000, 'task-1', 'TRANSCRIPTION', 'SUCCESS', '', 'ciao', 1234, 5.0)"
        )
        sql.version = 2
        sql.close()
    }

    private fun sampleLog(id: String, taskId: String, isPartial: Boolean, failedChunkCount: Int) = LogEntity(
        id = id,
        timestamp = 1700000000000,
        taskId = taskId,
        type = "TRANSCRIPTION",
        status = "SUCCESS",
        result = "ciao",
        isPartial = isPartial,
        failedChunkCount = failedChunkCount
    )

    companion object {
        private const val DB_NAME = "migration-test.db"
    }
}
