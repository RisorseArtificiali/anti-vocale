package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.ModelCatalogJson
import java.io.File

/**
 * Seeds [BundledCatalog] from the real bundled asset (read from disk) for unit
 * tests that drive catalog-dependent code (registry, managers, downloaders)
 * without Android assets. Mirrors the probe in
 * [TranscriptionOrchestratorTestBase] / [com.antivocale.app.data.catalog.BundledModelCatalogTest].
 * Idempotent: [BundledCatalog.seed] simply replaces the cache.
 */
fun seedCatalogForTest() {
    BundledCatalog.seed(bundledCatalogEntriesForTest())
}

/**
 * The parsed entries of the real bundled models_catalog.json, the one asset
 * probe for tests that reason about the catalog itself (metadata policy,
 * fixtures contract) without seeding the singleton. TASK-413: the
 * metadata-policy tests own no private copy of this probe.
 */
fun bundledCatalogEntriesForTest(): List<CatalogEntry> {
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
