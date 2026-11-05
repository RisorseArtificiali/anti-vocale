package com.antivocale.app.data

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins TASK-963: the automatic repair of wiped external imports. The
 * platform-cleaner failure class deletes a record's directory while the
 * record survives dangling; the repairer re-imports from the record's own
 * sourceUrl and restores the selections captured before
 * DanglingBackendCleaner could reset them. The same-id survival the
 * restore depends on is the reimport seam's contract (see the repairer's
 * KDoc for the dedupe-repoint chain).
 */
class ExternalImportRepairerTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: java.io.File

    /** Records what the re-import seam was called with, per call. */
    private val calls = mutableListOf<ExternalModelRecord>()
    /** Fails the next re-import when set (cleared after one throw). */
    private var nextFailure: Exception? = null

    private val reimport: suspend (ExternalModelRecord) -> ExternalModelRecord = { record ->
        nextFailure?.let { throw it }
        calls += record
        // The repair's postcondition from the store's point of view: the
        // record's dir exists again (same id: the real dedupe re-reads the
        // store and updates the record to the fresh copy; the fake mirrors
        // that store update on its single-threaded snapshot).
        val dir = java.io.File(filesRoot, "${record.id}-repaired")
        dir.mkdirs()
        val repaired = record.copy(dir = dir.absolutePath)
        store.update(repaired)
        repaired
    }

    /** A re-import that "succeeds" without producing a loadable dir: the
     *  restore gate must refuse to re-point the preference at it. */
    private val reimportLeavingItDangling: suspend (ExternalModelRecord) -> ExternalModelRecord = { record ->
        calls += record
        record
    }

    /** Simulates upstream content drift: the re-import registers a FRESH
     *  record (new id, loadable dir) instead of repointing the old one. */
    private val driftingReimport: suspend (ExternalModelRecord) -> ExternalModelRecord = { record ->
        calls += record
        val dir = java.io.File(filesRoot, "${record.id}-fresh-copy")
        dir.mkdirs()
        val fresh = record.copy(id = "${record.id}-new", dir = dir.absolutePath)
        store.add(fresh)
        fresh
    }

    private fun record(
        id: String,
        dirPresent: Boolean,
        source: ExternalModelSource = ExternalModelSource.URL,
        sourceUrl: String? = "https://example.com/$id.json",
        quarantined: Boolean = false,
        streaming: Boolean = false,
        options: Map<String, String> = emptyMap(),
    ): ExternalModelRecord {
        val dir = java.io.File(filesRoot, "$id-dir")
        if (dirPresent) dir.mkdirs()
        return ExternalModelRecord(
            id = id,
            displayName = "Model $id",
            dir = dir.absolutePath,
            family = ModelFamily.CANARY,
            modelType = "",
            languages = listOf("nl"),
            source = source,
            sourceUrl = sourceUrl,
            files = emptyMap(),
            sizeBytes = 1L,
            importedAt = 0L,
            options = options,
            streaming = streaming,
            quarantined = quarantined,
        )
    }

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("import-repairer").toFile()
    }

    @Test
    fun `wiped url record is re-imported from its own source`() = runTest {
        store.add(record("wiped", dirPresent = false))

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = reimport)
        repairer.capture()
        val repaired = repairer.repairAll()

        assertEquals(listOf("wiped"), repaired)
        assertEquals(1, calls.size)
        assertEquals("https://example.com/wiped.json", calls[0].sourceUrl)
    }

    @Test
    fun `active selection and refinement pin of a wiped record are restored after repair`() = runTest {
        store.add(record("active-one", dirPresent = false, options = mapOf("canary.language" to "nl"), streaming = true))
        prefs._transcriptionBackend.value = "external:active-one"
        prefs._refinementModelBackendId.value = "external:active-one"

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = reimport)
        repairer.capture()
        // The cleaner's reset happens between capture and repair; the
        // repairer must still restore what was captured. The seam carries
        // the record's own parameters through (the production lambda feeds
        // them to importFromUrl).
        prefs._transcriptionBackend.value = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
        prefs._refinementModelBackendId.value = ""
        val repaired = repairer.repairAll()

        assertEquals(listOf("active-one"), repaired)
        assertEquals("external:active-one", prefs._transcriptionBackend.value)
        assertEquals("external:active-one", prefs._refinementModelBackendId.value)
        assertEquals(mapOf("canary.language" to "nl"), calls[0].options)
        assertTrue(calls[0].streaming)
    }

    @Test
    fun `selection of a record that still failed repair is not restored`() = runTest {
        store.add(record("still-gone", dirPresent = false))
        prefs._transcriptionBackend.value = "external:still-gone"
        nextFailure = java.io.IOException("network gone")

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = reimport)
        repairer.capture()
        prefs._transcriptionBackend.value = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
        val repaired = repairer.repairAll()

        assertEquals(emptyList<String>(), repaired)
        // Not restored to a record that still cannot load.
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `successful re-import that leaves the record unloadable does not restore the selection`() = runTest {
        store.add(record("phantom", dirPresent = false))
        prefs._transcriptionBackend.value = "external:phantom"

        val repairer = ExternalImportRepairer(
            store, prefs, isMetered = { false }, reimport = reimportLeavingItDangling)
        repairer.capture()
        prefs._transcriptionBackend.value = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
        val repaired = repairer.repairAll()

        // Not counted as repaired either: the completion log must not
        // claim a restore that did not happen (review round), and the
        // preference must stay on the default (the loadability gate).
        assertEquals(emptyList<String>(), repaired)
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, prefs._transcriptionBackend.value)
    }

    @Test
    fun `a newer explicit user selection made during the repair wins`() = runTest {
        store.add(record("slow", dirPresent = false))
        prefs._transcriptionBackend.value = "external:slow"

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = reimport)
        repairer.capture()
        prefs._transcriptionBackend.value = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
        // The user picks a different model while the gigabytes download:
        // the restore (inside repairAll) must see this and keep it.
        prefs._transcriptionBackend.value = "parakeet-tdt"
        repairer.repairAll()

        assertEquals("parakeet-tdt", prefs._transcriptionBackend.value)
    }

    @Test
    fun `upstream drift deletes the stale record and re-points the selection at the fresh id`() = runTest {
        store.add(record("drifted", dirPresent = false))
        prefs._transcriptionBackend.value = "external:drifted"

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = driftingReimport)
        repairer.capture()
        prefs._transcriptionBackend.value = PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND
        val repaired = repairer.repairAll()

        // The loadable id is the fresh one, and the stale record is gone.
        assertEquals(listOf("drifted-new"), repaired)
        assertEquals("external:drifted-new", prefs._transcriptionBackend.value)
        assertTrue(store.records().none { it.id == "drifted" })
    }

    @Test
    fun `metered network defers the repair entirely`() = runTest {
        store.add(record("wifi-only", dirPresent = false))
        val repairer = ExternalImportRepairer(store, prefs, isMetered = { true }, reimport = reimport)
        repairer.capture()
        val repaired = repairer.repairAll()
        assertEquals(emptyList<String>(), repaired)
        assertEquals(0, calls.size)
    }

    @Test
    fun `non-url records, quarantined records, and intact records are not candidates`() = runTest {
        store.add(record("local-sourced", dirPresent = false, source = ExternalModelSource.LOCAL))
        store.add(record("no-source-url", dirPresent = false, sourceUrl = null))
        store.add(record("quarantined", dirPresent = false, quarantined = true))
        store.add(record("intact", dirPresent = true))

        val repairer = ExternalImportRepairer(store, prefs, isMetered = { false }, reimport = reimport)
        repairer.capture()
        val repaired = repairer.repairAll()

        assertEquals(emptyList<String>(), repaired)
        assertEquals(0, calls.size)
    }
}
