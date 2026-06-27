package com.antivocale.app.data

import com.antivocale.app.data.ModelDownloader.ModelVariant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests the artifact version-stamp logic in [ModelDownloader] — the sidecar `.version`
 * marker that drives the "update available" prompt for stale Gemma `.litertlm` copies
 * (TASK-236: the 2026-05-05 MTP-drafter refresh replaced the monolithic file under the
 * same `/resolve/main/<name>` path, so filename-based caching is unsound).
 *
 * These exercise the pure File-based helpers directly — no Android [android.content.Context]
 * or Robolectric needed. The variant constants carry only Int resource ids, so they
 * instantiate fine on plain JVM.
 */
class ModelDownloaderVersionTest {

    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        modelsDir = File(Files.createTempDirectory("model-downloader-version-test").toUri())
    }

    @After
    fun tearDown() {
        modelsDir.listFiles()?.forEach { it.delete() }
        modelsDir.delete()
    }

    /** A freshly downloaded variant gets a marker equal to its required version. */
    @Test
    fun writeVersionMarker_recordsCurrentVersion() {
        ModelDownloader.writeVersionMarker(modelsDir, ModelVariant.GEMMA_4_E2B)
        assertEquals(2, ModelDownloader.readInstalledVersion(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** A pre-versioning download (no marker sidecar) reads as VERSION_UNKNOWN (0). */
    @Test
    fun readInstalledVersion_unknownWhenNoMarker() {
        assertEquals(0, ModelDownloader.readInstalledVersion(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** Corrupted marker content falls back to VERSION_UNKNOWN rather than throwing. */
    @Test
    fun readInstalledVersion_unknownWhenCorruptMarker() {
        ModelDownloader.versionMarkerFile(modelsDir, ModelVariant.GEMMA_4_E2B).writeText("not-a-number")
        assertEquals(0, ModelDownloader.readInstalledVersion(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** Stale: file present, no marker, required version > 1 → update available (the TASK-236 case). */
    @Test
    fun isModelUpdateAvailable_preVersioningE2bInstall_isStale() {
        File(modelsDir, ModelVariant.GEMMA_4_E2B.fileName).writeText("fake") // file present, no marker
        assertTrue(ModelDownloader.isModelUpdateAvailable(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** Fresh E2B install (marker == required version) → not stale. */
    @Test
    fun isModelUpdateAvailable_freshE2bInstall_isNotStale() {
        File(modelsDir, ModelVariant.GEMMA_4_E2B.fileName).writeText("fake")
        ModelDownloader.writeVersionMarker(modelsDir, ModelVariant.GEMMA_4_E2B)
        assertFalse(ModelDownloader.isModelUpdateAvailable(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** Gemma 3n (modelVersion 1, no content migration) is NEVER flagged, even without a marker. */
    @Test
    fun isModelUpdateAvailable_gemma3n_isNeverStale() {
        File(modelsDir, ModelVariant.GEMMA_3N_E2B.fileName).writeText("fake") // no marker
        assertFalse(ModelDownloader.isModelUpdateAvailable(modelsDir, ModelVariant.GEMMA_3N_E2B))
    }

    /** Missing file → never stale (nothing installed to update). */
    @Test
    fun isModelUpdateAvailable_missingFile_isNotStale() {
        assertFalse(ModelDownloader.isModelUpdateAvailable(modelsDir, ModelVariant.GEMMA_4_E2B))
    }

    /** deleteModel's marker cleanup (used by the update flow) removes an existing marker. */
    @Test
    fun deleteVersionMarker_removesExistingMarker() {
        File(modelsDir, ModelVariant.GEMMA_4_E2B.fileName).writeText("fake")
        ModelDownloader.writeVersionMarker(modelsDir, ModelVariant.GEMMA_4_E2B)
        assertTrue(ModelDownloader.versionMarkerFile(modelsDir, ModelVariant.GEMMA_4_E2B).exists())

        assertTrue(ModelDownloader.deleteVersionMarker(modelsDir, ModelVariant.GEMMA_4_E2B))
        assertFalse(ModelDownloader.versionMarkerFile(modelsDir, ModelVariant.GEMMA_4_E2B).exists())
    }

    /** deleteVersionMarker is a no-op (returns false) when no marker exists. */
    @Test
    fun deleteVersionMarker_noMarker_returnsFalse() {
        assertFalse(ModelDownloader.deleteVersionMarker(modelsDir, ModelVariant.GEMMA_4_E2B))
    }
}
