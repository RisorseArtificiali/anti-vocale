package com.antivocale.app.transcription

import com.antivocale.app.testing.RepoFiles
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.ModelCatalogJson

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
    val asset = RepoFiles.resolve("src/main/assets/models_catalog.json")
    return ModelCatalogJson.parseCatalog(asset.readText())
}
