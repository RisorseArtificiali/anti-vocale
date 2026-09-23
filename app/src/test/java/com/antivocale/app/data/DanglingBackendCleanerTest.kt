package com.antivocale.app.data

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the startup hygiene pass over the persisted transcription backend
 * (TASK-342 defect 2): a backend preference that points at an external record
 * which no longer resolves (deleted record, or record whose directory vanished)
 * must be reset to the default backend, otherwise every transcription request
 * fails on a backend id nothing can load.
 *
 * Uses [FakePreferencesManager] and a real temp directory for the dirExists
 * check, mirroring [ExternalModelStoreTest].
 */
class DanglingBackendCleanerTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: java.io.File

    private fun record(id: String, dir: String) = ExternalModelRecord(
        id = id,
        displayName = "Model $id",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = emptyList(),
        source = ExternalModelSource.URL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
    )

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("dangling-cleaner").toFile()
    }

    @Test
    fun `dangling external id with no record is reset to default`() = runTest {
        prefs._transcriptionBackend.value = "external:gone-id"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `external id whose record exists but directory vanished is reset to default`() = runTest {
        val dir = java.io.File(filesRoot, "vanished")
        val saved = record("half-gone", dir.absolutePath)
        store.add(saved)
        prefs._transcriptionBackend.value = saved.backendId
        // Directory never created: byId() uses validRecords, so the record is unreachable.

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `valid external record is left active`() = runTest {
        val dir = java.io.File(filesRoot, "present").apply { mkdirs() }
        val saved = record("present-id", dir.absolutePath)
        store.add(saved)
        prefs._transcriptionBackend.value = saved.backendId

        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(saved.backendId, prefs._transcriptionBackend.value)
    }

    @Test
    fun `builtin backend is left untouched`() = runTest {
        prefs._transcriptionBackend.value = "whisper"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals("whisper", prefs._transcriptionBackend.value)
    }

    @Test
    fun `retired gguf backend id is reset to default`() = runTest {
        // TASK-639: the loader is gone; without the reset the stale id would
        // fall through to the LLM loader and cold-reload it on every request.
        prefs._transcriptionBackend.value = "gemma4_gguf"
        DanglingBackendCleaner(prefs, store).cleanIfNeeded()
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }
}
