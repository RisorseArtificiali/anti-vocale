package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.ModelCatalogJson
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Catalog parity for [ModelInfoProvider]: every bundled catalog variant must
 * resolve to a [ModelInfo], so the info overlay never degrades to
 * languages-only for a model the UI offers. A catalog addition that forgets
 * its ModelInfo fails here (fail-fast, same as the other parity tests).
 */
class ModelInfoProviderCatalogTest {

    @Test
    fun `every catalog variant dir-name resolves to model info`() {
        val catalog = parseRealCatalog()
        assertTrue("catalog must not be empty", catalog.isNotEmpty())
        for (entry in catalog) {
            for (variant in entry.variants) {
                assertNotNull(
                    "catalog variant '${entry.id}/${variant.dirName}' lacks ModelInfo",
                    ModelInfoProvider.getInfoByDirName(variant.dirName),
                )
            }
        }
    }

    @Test
    fun `both parakeet variant dir-names resolve`() {
        val parakeet = parseRealCatalog().first { it.id == "sherpa-onnx" }
        for (variant in parakeet.variants) {
            assertNotNull(
                "parakeet variant '${variant.dirName}' must resolve",
                ModelInfoProvider.getInfoByDirName(variant.dirName),
            )
        }
    }

    private fun parseRealCatalog() =
        runCatching { ModelCatalogJson.parseCatalog(catalogAsset().readText()) }.getOrThrow()

    private fun catalogAsset(): File {
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate models_catalog.json from ${File(".").absolutePath}")
        }
    }
}