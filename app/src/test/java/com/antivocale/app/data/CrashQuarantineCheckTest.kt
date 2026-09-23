package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TASK-640: a leaked pendingBackendLoad marker (only a native death leaves it
 * set) quarantines the external record and clears the marker; non-external ids
 * only clear the marker; a missing marker is a no-op. The notification side is
 * Android-bound and covered by the device pass, not here.
 */
class CrashQuarantineCheckTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        store = ExternalModelStore(prefs)
        filesRoot = Files.createTempDirectory("crash-quarantine").toFile()
    }

    private fun record(id: String, dir: File): ExternalModelRecord {
        dir.mkdirs()
        return ExternalModelRecord(
            id = id, displayName = "model-$id", dir = dir.absolutePath,
            family = ModelFamily.CTC, modelType = "nemo_ctc", languages = listOf("uk"),
            source = ExternalModelSource.URL, sourceUrl = "https://example.invalid/$id",
            files = emptyMap(), sizeBytes = 1, importedAt = 0,
        )
    }

    @Test
    fun `leaked marker for an external id quarantines the record and clears the marker`() = runTest {
        val saved = record("doomed", File(filesRoot, "doomed"))
        store.add(saved)
        prefs._pendingBackendLoad.value = saved.backendId

        CrashQuarantineCheck(prefs, store).apply()

        assertNull(prefs._pendingBackendLoad.value)
        assertTrue(store.records().first { it.id == "doomed" }.quarantined)
        // Quarantined records stop resolving as loadable backends.
        assertNull(store.byId("doomed"))
    }

    @Test
    fun `leaked marker for a non external id only clears the marker`() = runTest {
        val saved = record("safe", File(filesRoot, "safe"))
        store.add(saved)
        prefs._pendingBackendLoad.value = "whisper"

        CrashQuarantineCheck(prefs, store).apply()

        assertNull(prefs._pendingBackendLoad.value)
        assertFalse(store.records().first { it.id == "safe" }.quarantined)
    }

    @Test
    fun `no marker is a no op`() = runTest {
        val saved = record("untouched", File(filesRoot, "untouched"))
        store.add(saved)

        CrashQuarantineCheck(prefs, store).apply()

        assertFalse(store.records().first { it.id == "untouched" }.quarantined)
    }

    @Test
    fun `already quarantined record is not re notified`() = runTest {
        val saved = record("already", File(filesRoot, "already")).copy(quarantined = true)
        store.add(saved)
        prefs._pendingBackendLoad.value = saved.backendId

        CrashQuarantineCheck(prefs, store).apply()

        assertNull(prefs._pendingBackendLoad.value)
        assertTrue(store.records().first { it.id == "already" }.quarantined)
    }

    @Test
    fun `marker for a deleted external record only clears the marker`() = runTest {
        prefs._pendingBackendLoad.value = "external:gone"

        CrashQuarantineCheck(prefs, store).apply()

        assertNull(prefs._pendingBackendLoad.value)
        assertEquals(emptyList<ExternalModelRecord>(), store.records())
    }
}
