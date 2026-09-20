package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        // Data-class equality: every entity column must survive both explicit
        // projections (the named historical offender gets its own message).
        assertNotNull(
            "firstPassTranscript missing from the list projections (2026-09-20 regression)",
            fromGetAll.firstPassTranscript)
        assertEquals(full, fromGetAll)
        assertEquals(full, fromSearch)
    }
}
