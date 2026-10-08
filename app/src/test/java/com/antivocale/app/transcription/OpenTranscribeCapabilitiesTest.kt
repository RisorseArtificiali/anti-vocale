package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-785: the capabilities derivation. supportedLanguages must reflect
 * what the model DECLARES (variant-overridden, entry-level fallback), not
 * the pin-offered set [TranscriptionLanguagePolicy.offeredLanguages]
 * derives (which gates on the catalog flags); modelReady follows the
 * loadability the pickers use.
 */
class OpenTranscribeCapabilitiesTest {

    private fun variant(dirName: String, languages: List<String> = emptyList()) = CatalogVariant(
        name = dirName,
        title = null,
        description = null,
        badgeKey = null,
        dirName = dirName,
        estimatedSizeMB = 100,
        languages = languages,
        source = CatalogSource(kind = "huggingface", repo = "some/repo"),
        files = emptyList(),
    )

    private fun entry(id: String, languages: List<String>, variants: List<CatalogVariant>): CatalogEntry =
        CatalogEntry(
            id = id,
            runtime = "offline",
            modelType = "nemo_transducer",
            family = "TRANSDUCER",
            display = com.antivocale.app.data.catalog.CatalogDisplay.Literal(id),
            variants = variants,
            languages = languages,
        )

    private fun external(languages: List<String>) = ExternalModelRecord(
        id = "ext-1",
        displayName = "Imported model",
        dir = "/data/models/external/imported-abc",
        family = ModelFamily.WHISPER,
        modelType = "",
        languages = languages,
        source = ExternalModelSource.URL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = 1,
        importedAt = 0,
    )

    @Test
    fun `the static contract facts hold`() {
        val caps = OpenTranscribeCapabilities.derive(
            versionName = "1.15.0-SNAPSHOT",
            modelPath = null,
            externalRecord = null,
            catalogEntry = null,
        )
        assertEquals(3, caps.contractVersion)
        assertEquals("anti-vocale", caps.engineId)
        assertEquals("1.15.0-SNAPSHOT", caps.engineVersion)
        assertTrue(caps.autoDetectLanguage)
        assertTrue(caps.cancellable)
        assertFalse(caps.streaming)
    }

    @Test
    fun `modelReady requires a saved path that still exists on disk`() {
        val entry = entry("sherpa-onnx", listOf("en", "it"), listOf(variant("v1")))
        val missing = OpenTranscribeCapabilities.derive("1.0", null, null, entry) { true }
        assertFalse(missing.modelReady)
        val vanished = OpenTranscribeCapabilities.derive("1.0", "/models/sherpa-onnx/v1", null, entry) { false }
        assertFalse(vanished.modelReady)
        val present = OpenTranscribeCapabilities.derive("1.0", "/models/sherpa-onnx/v1", null, entry) { true }
        assertTrue(present.modelReady)
    }

    @Test
    fun `an external record is the ready state and contributes its own languages`() {
        val caps = OpenTranscribeCapabilities.derive(
            versionName = "1.0",
            modelPath = null,
            externalRecord = external(listOf("ru", "en")),
            catalogEntry = null,
        )
        assertTrue(caps.modelReady)
        assertArrayEquals(arrayOf("ru", "en"), caps.supportedLanguages)
    }

    @Test
    fun `an external record without declared languages reports null`() {
        val caps = OpenTranscribeCapabilities.derive("1.0", null, external(emptyList()), null)
        assertNull(caps.supportedLanguages)
    }

    @Test
    fun `the installed variant's languages win over the entry-level list`() {
        val entry = entry(
            "whisper",
            listOf("en"),
            listOf(variant("multilingual", listOf("en", "fr", "de")), variant("distil-it", listOf("it"))),
        )
        val caps = OpenTranscribeCapabilities.derive("1.0", "/models/whisper/distil-it", null, entry) { true }
        assertArrayEquals(arrayOf("it"), caps.supportedLanguages)
    }

    @Test
    fun `an unknown directory falls back to the default variant and its languages`() {
        val entry = entry(
            "whisper",
            listOf("en"),
            listOf(
                variant("distil-it", listOf("it")),
                variant("multilingual", listOf("en", "fr")),
            ),
        )
        // No flags.defaultVariant declared: the FIRST variant is the default.
        val unknownDir = OpenTranscribeCapabilities.derive("1.0", "/models/whisper/mystery", null, entry) { true }
        assertArrayEquals(arrayOf("it"), unknownDir.supportedLanguages)
        // No path at all: same default-variant resolution.
        val noPath = OpenTranscribeCapabilities.derive("1.0", null, null, entry) { true }
        assertArrayEquals(arrayOf("it"), noPath.supportedLanguages)
    }

    @Test
    fun `a variant without its own list falls back to the entry-level languages`() {
        val entry = entry("qwen3-asr", listOf("en", "zh"), listOf(variant("v1")))
        val caps = OpenTranscribeCapabilities.derive("1.0", "/models/qwen3-asr/v1", null, entry) { true }
        assertArrayEquals(arrayOf("en", "zh"), caps.supportedLanguages)
    }

    @Test
    fun `a backend with no catalog entry and no record reports null languages`() {
        val caps = OpenTranscribeCapabilities.derive("1.0", "/models/gemma/model.task", null, null) { true }
        assertTrue(caps.modelReady)
        assertNull(caps.supportedLanguages)
    }

    @Test
    fun `the degraded shape is honest`() {
        val caps = OpenTranscribeCapabilities.derive("1.0", null, null, null)
        assertFalse(caps.modelReady)
        assertNull(caps.supportedLanguages)
    }
}
