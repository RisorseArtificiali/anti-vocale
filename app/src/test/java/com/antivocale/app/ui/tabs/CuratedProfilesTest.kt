package com.antivocale.app.ui.tabs

import com.antivocale.app.data.ExternalCatalog
import com.antivocale.app.data.catalog.ModelCatalogJson
import com.antivocale.app.transcription.Language
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract guard for the curated Model-tab profiles (GH #70): every
 * recommendation must resolve against the REAL shipped data (the bundled
 * catalog asset for entry ids/variants, the bundled community index for
 * catalog names), every recommendation must actually serve the profile's
 * language, and the language resolution must follow the same code conventions
 * as the language filter. A broken seed fails here instead of rendering an
 * empty row on a user's phone.
 *
 * Reads both assets from disk (module dir or repo root, the
 * StringResourceParityTest/BundledModelCatalogTest pattern).
 */
class CuratedProfilesTest {

    private fun asset(path: String): File {
        val moduleRelative = File(path)
        val rootRelative = File("app/$path")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate $path from ${File(".").absolutePath}")
        }
    }

    private fun bundledCatalog() =
        ModelCatalogJson.parseCatalog(asset("src/main/assets/models_catalog.json").readText())

    private fun communityIndex() =
        ExternalCatalog.parseIndex(asset("src/main/assets/external-catalog/index.json").readText())

    @Test
    fun `launch seeds are italian german and spanish`() {
        assertEquals(listOf("it", "de", "es"), CuratedProfiles.PROFILES.map { it.language })
        // Every profile must carry at least one recommendation to be worth showing.
        CuratedProfiles.PROFILES.forEach {
            assertTrue("profile ${it.language} has no recommendations", it.recommendations.isNotEmpty())
        }
    }

    @Test
    fun `bundled recommendations resolve to real catalog entries and variants`() {
        val catalog = bundledCatalog().associateBy { it.id }
        CuratedProfiles.PROFILES.forEach { profile ->
            profile.recommendations
                .filterIsInstance<CuratedProfiles.Recommendation.Bundled>()
                .forEach { rec ->
                    val entry = catalog[rec.entryId]
                    assertNotNull(
                        "profile ${profile.language}: bundled id '${rec.entryId}' is not in models_catalog.json",
                        entry)
                    entry ?: return@forEach
                    val variantNames = entry.variants.map { it.name }
                    rec.variantNames?.forEach { pinned ->
                        assertTrue(
                            "profile ${profile.language}: '${rec.entryId}' has no variant '$pinned'",
                            pinned in variantNames)
                    }
                }
        }
    }

    @Test
    fun `bundled recommendations serve the profile language`() {
        val catalog = bundledCatalog().associateBy { it.id }
        CuratedProfiles.PROFILES.forEach { profile ->
            profile.recommendations
                .filterIsInstance<CuratedProfiles.Recommendation.Bundled>()
                .forEach { rec ->
                    val entry = catalog.getValue(rec.entryId)
                    val picked = rec.variantNames?.let { pinned ->
                        entry.variants.filter { it.name in pinned }
                    } ?: entry.variants
                    assertTrue(
                        "profile ${profile.language}: '${rec.entryId}' recommendation covers no variant",
                        picked.isNotEmpty())
                    assertTrue(
                        "profile ${profile.language}: no '${rec.entryId}' variant declares ${profile.language}",
                        picked.any { profile.language in entry.languagesFor(it) })
                }
        }
    }

    @Test
    fun `community recommendations resolve to real index names and serve the profile language`() {
        val index = communityIndex().associateBy { it.name }
        CuratedProfiles.PROFILES.forEach { profile ->
            profile.recommendations
                .filterIsInstance<CuratedProfiles.Recommendation.Community>()
                .forEach { rec ->
                    val entry = index[rec.catalogName]
                    assertNotNull(
                        "profile ${profile.language}: community name '${rec.catalogName}' is not in the bundled index",
                        entry)
                    entry ?: return@forEach
                    assertTrue(
                        "profile ${profile.language}: '${rec.catalogName}' does not declare ${profile.language}",
                        profile.language in entry.languages)
                }
        }
    }

    @Test
    fun `profile languages are language-filter codes`() {
        // Same convention as the filter dropdown (Language.FILTER_ENTRIES) and
        // the TranscriptionLanguagePolicy code sets: lowercase ISO 639-1. A
        // profile outside the filter could never be reached or cross-checked
        // by the user through the primary tool.
        CuratedProfiles.PROFILES.forEach {
            assertTrue(
                "profile language ${it.language} is not a filter entry",
                it.language in Language.FILTER_ENTRIES)
        }
    }

    @Test
    fun `forLanguage resolves primary subtags case-insensitively and drops regions`() {
        assertEquals("it", CuratedProfiles.forLanguage("it")?.language)
        assertEquals("it", CuratedProfiles.forLanguage("IT")?.language)
        assertEquals("it", CuratedProfiles.forLanguage("it-IT")?.language)
        // A Swiss-German phone resolves to the German profile (dialect entry included).
        assertEquals("de", CuratedProfiles.forLanguage("de-CH")?.language)
        // Both BCP-47 separators are accepted.
        assertEquals("es", CuratedProfiles.forLanguage("es_419")?.language)
        // Unprofiled languages and blanks degrade to no section.
        assertEquals(null, CuratedProfiles.forLanguage("en"))
        assertEquals(null, CuratedProfiles.forLanguage("en-US"))
        assertEquals(null, CuratedProfiles.forLanguage(null))
        assertEquals(null, CuratedProfiles.forLanguage(""))
        assertEquals(null, CuratedProfiles.forLanguage("   "))
    }

    @Test
    fun `suggestion link points at the community-input issue`() {
        assertEquals(
            "https://github.com/RisorseArtificiali/anti-vocale/issues/70",
            CuratedProfiles.SUGGESTION_ISSUE_URL)
    }
}
