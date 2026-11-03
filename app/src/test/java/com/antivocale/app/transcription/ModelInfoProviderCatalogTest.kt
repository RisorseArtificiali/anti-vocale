package com.antivocale.app.transcription

import com.antivocale.app.testing.RepoFiles
import com.antivocale.app.data.catalog.ModelCatalogJson
import com.antivocale.app.data.catalog.PlaceholderSlot
import org.junit.Assert.assertEquals
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

    @Test
    fun `the whistle best-for claim declares its size and count slots`() {
        // TASK-947: the model-info overlay maps bestForPlaceholders onto the
        // variant's catalog size and language count; the bash guard pins the
        // string side (%1$d/%2$d survive in every locale), this pins the
        // render-site side (a slot-list edit cannot desync the two).
        val info = ModelInfoProvider.getInfoByDirName("whistle-v1")
            ?: error("whistle model info missing")
        assertEquals(
            "the whistle best-for string carries %1\$d size then %2\$d count",
            listOf(PlaceholderSlot.SIZE, PlaceholderSlot.COUNT),
            info.bestForPlaceholders,
        )
        // TASK-947 review: the slot list also lives as a one-line literal in
        // the bash guard's static entry; this fails the pair if either side
        // gains a slot without the other.
        val declared = Regex("PREFIXED\\['model_info_best_for_whistle'\\] = \\[(.*?)\\]")
            .find(RepoFiles.readText("scripts/check-model-sizes.sh"))
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('\'') }
        assertEquals(listOf("size", "count"), declared)
    }

    private fun parseRealCatalog() =
        runCatching { ModelCatalogJson.parseCatalog(catalogAsset().readText()) }.getOrThrow()

    private fun catalogAsset(): File = RepoFiles.resolve("src/main/assets/models_catalog.json")
}