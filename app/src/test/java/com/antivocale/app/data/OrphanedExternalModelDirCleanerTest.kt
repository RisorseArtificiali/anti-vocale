package com.antivocale.app.data

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the external-root reconciliation sweep (TASK-657, GH #117): dirs under
 * the external models root whose record is gone are reclaimed, while every
 * live record's dir (a quarantined one included) is never touched. Mirrors
 * [DanglingBackendCleanerTest]: FakePreferencesManager plus a real temp root.
 */
class OrphanedExternalModelDirCleanerTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: java.io.File

    private fun record(id: String, dir: String, quarantined: Boolean = false) = ExternalModelRecord(
        id = id,
        displayName = "Model $id",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = emptyList(),
        // URL source with no catalog entry: a valid install the sweep must keep.
        source = ExternalModelSource.URL,
        sourceUrl = "https://example.com/some-repo",
        files = emptyMap(),
        sizeBytes = 1L,
        importedAt = 0L,
        quarantined = quarantined,
    )

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("ext-dir-sweep").toFile()
    }

    // ---- runner: the delegation, the ownership set, the grace guard ----

    private fun writeDir(name: String, bytes: Long): java.io.File =
        java.io.File(filesRoot, name).apply {
            mkdirs()
            java.io.File(this, "model.int8.onnx").writeBytes(ByteArray(bytes.toInt()))
            // Stale by default so the grace window does not protect test
            // fixtures; the grace test resets its fresh dir explicitly.
            setLastModified(System.currentTimeMillis() - 3 * 60 * 60 * 1000L)
        }

    @Test
    fun `orphan dir reclaimed with exact bytes while the live record dir stays`() = runTest {
        store.add(record("live-1", writeDir("parakeet-t0-9z8y7x", 1024).absolutePath))
        writeDir("gigaam-v3-zzzzzz", 2048)

        val reclaimed = OrphanedExternalModelDirCleaner(store) { filesRoot }.cleanIfNeeded()

        assertEquals(2048L, reclaimed)
        assertFalse(java.io.File(filesRoot, "gigaam-v3-zzzzzz").exists())
        assertTrue(java.io.File(filesRoot, "parakeet-t0-9z8y7x").exists())
    }

    @Test
    fun `a quarantined record still owns its dir`() = runTest {
        store.add(record("q-1", writeDir("sense-voice-q1w2e3", 512).absolutePath, quarantined = true))

        val reclaimed = OrphanedExternalModelDirCleaner(store) { filesRoot }.cleanIfNeeded()

        assertEquals(0L, reclaimed)
        assertTrue(java.io.File(filesRoot, "sense-voice-q1w2e3").exists())
    }

    @Test
    fun `a record dir under a different filesDir prefix protects the same-named on-root dir`() = runTest {
        // Review F3: the name-based contract. A backup restore to another
        // user id moves the record's absolute prefix; the on-root dir of the
        // same terminal name must survive, and a real orphan still sweeps.
        store.add(record(
            "restored-1", "/data/user/10/com.antivocale.app/files/models/external/parakeet-t0-9z8y7x"))
        writeDir("parakeet-t0-9z8y7x", 1024)
        writeDir("gigaam-v3-orphan", 512)

        val reclaimed = OrphanedExternalModelDirCleaner(store) { filesRoot }.cleanIfNeeded()

        assertEquals(512L, reclaimed)
        assertTrue(java.io.File(filesRoot, "parakeet-t0-9z8y7x").exists())
        assertFalse(java.io.File(filesRoot, "gigaam-v3-orphan").exists())
    }

    @Test
    fun `a recently modified dir is protected by the grace window even with no record`() = runTest {
        // Review F2: the structural in-flight guard. A fresh dir (an import
        // just started, record not yet written) must survive; a stale orphan
        // sweeps.
        val fresh = writeDir("fresh-import-x1y2z3", 1024)
        fresh.setLastModified(System.currentTimeMillis())
        val stale = writeDir("stale-orphan-a1b2c3", 512)
        stale.setLastModified(System.currentTimeMillis() - 3 * 60 * 60 * 1000L)

        val reclaimed = OrphanedExternalModelDirCleaner(store) { filesRoot }.cleanIfNeeded()

        assertEquals(512L, reclaimed)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `missing root is a no-op`() = runTest {
        val missing = java.io.File(filesRoot, "not-there")
        val reclaimed = OrphanedExternalModelDirCleaner(store) { missing }.cleanIfNeeded()
        assertEquals(0L, reclaimed)
    }
}
