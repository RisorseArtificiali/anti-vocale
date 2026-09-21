package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GH #43 device trial (2026-09-20): the list queries project explicit
 * columns, and a column added to LogEntity without touching both
 * projections silently reads back as null (the row HAD the first pass;
 * the expanded card never rendered it). Pins every projected column
 * against the entity so the next added column fails this test, not a
 * device trial.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LogDaoProjectionTest {

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
        try { db.close() } catch (_: Exception) {}
    }

    /**
     * TASK-595 F4: the old fixture left nullable fields at their null
     * defaults, so a column missing from BOTH projections read as null on
     * both sides of the data-class equality and the pin stayed green with a
     * real gap (segments was exactly that). Every nullable field is now
     * populated non-null, and the columns the list projections deliberately
     * EXCLUDE are an explicit allowlist asserted null, not an accident:
     * adding a LogEntity column without touching this test or the lists
     * fails HERE first.
     */
    private val leanAllowlist = setOf("segments", "firstPassTranscript")

    @Test
    fun `both list projections return every entity column`() = runBlocking {
        val full = LogEntity(
            id = "id",
            timestamp = 1_000_000L,
            taskId = "task",
            type = "AUDIO",
            status = "SUCCESS",
            prompt = "p",
            result = "r",
            errorMessage = "e",
            durationMs = 16_000L,
            filePath = "/f",
            audioDurationSeconds = 2.0,
            sourcePackageName = "pkg",
            isPartial = false,
            failedChunkCount = 1,
            modelName = "m",
            rawTranscript = "raw",
            // The lean columns too: a null here would hide a projection gap.
            segments = """[{"startMs":0,"endMs":9,"text":"c"}]""",
            summary = "s",
            summarySkipReason = "skip",
            failureContext = "fx",
            processingContext = "{}",
            firstPassTranscript = "first",
            detectedLanguage = "de",
            languagePin = "it",
        )
        dao.insert(full)

        val fromGetAll = dao.getAll().first().single()
        val fromSearch = dao.searchAll("r").first().single()

        // Per-field assertions against the allowlist, not bare data-class
        // equality: equality alone cannot distinguish "excluded" from
        // "coincidentally null".
        for (field in LogEntity::class.java.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val name = field.name
            val written = field.get(full)
            if (name !in leanAllowlist) {
                // Mechanically enforce the fixture rule: a nullable field
                // left null in the fixture re-opens the F4 hole (null
                // equals null, pin green with a real projection gap).
                assertNotNull("fixture left $name null: populate it non-null", written)
            }
            val read = field.get(fromGetAll)
            if (name in leanAllowlist) {
                assertNull(
                    "$name is in the lean allowlist but the getAll projection " +
                        "carried it: either the allowlist is stale or the " +
                        "projection grew a heavy column",
                    read)
            } else {
                assertEquals(
                    "$name lost or altered by the getAll projection " +
                        "(add it to BOTH list queries or remove the column)",
                    written, read)
            }
            val searched = field.get(fromSearch)
            if (name in leanAllowlist) {
                assertNull("$name leaked through the searchAll projection", searched)
            } else {
                assertEquals("$name lost or altered by the searchAll projection", written, searched)
            }
        }
    }
}
