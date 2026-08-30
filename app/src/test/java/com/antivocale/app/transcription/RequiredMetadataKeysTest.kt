package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogFile
import com.antivocale.app.data.catalog.CatalogFlags
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.catalog.ModelCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for [SherpaBackend.requiredMetadataKeys], the catalog-driven
 * pre-native encoder metadata check: flags.metaKeys wins when declared, else the
 * modelType default (nemo → vocab+subsampling+model_type, everything else → vocab).
 */
class RequiredMetadataKeysTest {

    // ---- Pure logic on synthetic entries ----

    @Test
    fun `nemo transducer without flags defaults to the nemo key set`() {
        val entry = entry(modelType = "nemo_transducer")
        assertEquals(
            listOf("vocab_size", "subsampling_factor", "model_type"),
            SherpaBackend.requiredMetadataKeys(entry),
        )
    }

    @Test
    fun `non-nemo modelType without flags defaults to vocab_size only`() {
        assertEquals(
            listOf("vocab_size"),
            SherpaBackend.requiredMetadataKeys(entry(modelType = "whisper")),
        )
        // Nemotron online uses an empty modelType.
        assertEquals(
            listOf("vocab_size"),
            SherpaBackend.requiredMetadataKeys(entry(modelType = "")),
        )
    }

    @Test
    fun `qwen3 asr requires no encoder metadata (GH #68)`() {
        assertEquals(
            emptyList<String>(),
            SherpaBackend.requiredMetadataKeys(entry(modelType = "qwen3_asr")),
        )
    }

    @Test
    fun `declared flags metaKeys override the modelType default`() {
        val entry = entry(
            modelType = "whisper",
            flags = CatalogFlags(metaKeys = listOf("vocab_size", "subsampling_factor")),
        )
        assertEquals(
            listOf("vocab_size", "subsampling_factor"),
            SherpaBackend.requiredMetadataKeys(entry),
        )
    }

    // ---- Real bundled catalog: every backend's effective key list ----

    @Test
    fun `bundled catalog entries resolve the expected encoder metadata keys`() {
        val catalog = parseRealCatalog()
        val expect = mapOf(
            "sherpa-onnx" to listOf("vocab_size", "subsampling_factor", "model_type"),
            "gigaam" to listOf("vocab_size", "subsampling_factor", "model_type"),
            // Online transducer: flags carry only vocab_size (nemo keys would reject it).
            "nemotron-streaming" to listOf("vocab_size"),
            // GH #68: no encoder metadata required (the export has none; the loader
            // reads none).
            "qwen3-asr" to emptyList(),
        )
        expect.forEach { (id, keys) ->
            val entry = requireNotNull(catalog.firstOrNull { it.id == id }) { "catalog missing $id" }
            assertEquals("$id metadata keys", keys, SherpaBackend.requiredMetadataKeys(entry))
        }
    }

    private fun entry(modelType: String, flags: CatalogFlags = CatalogFlags()): CatalogEntry =
        CatalogEntry(
            id = "test",
            runtime = "offline",
            modelType = modelType,
            family = "TRANSDUCER",
            display = CatalogDisplay.Literal("Test"),
            flags = flags,
            variants = listOf(
                CatalogVariant(
                    name = "default",
                    dirName = "test",
                    estimatedSizeMB = 0,
                    source = CatalogSource(kind = "url", template = "https://example.test/{file}"),
                    files = listOf(CatalogFile(name = "encoder.onnx")),
                )
            ),
        )

    private fun parseRealCatalog(): List<CatalogEntry> {
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate models_catalog.json from ${File(".").absolutePath}")
        }
        return ModelCatalogJson.parseCatalog(asset.readText())
    }
}