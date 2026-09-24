package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bundled external-model catalog index: the pure query matcher
 * and the index parsing (TASK-331 Task 13). The matcher is deliberately dumb and
 * token-based: it must stay pure so the URL-dialog autocomplete is testable.
 */
class ExternalCatalogTest {

    companion object {
        /**
         * The app's bundled index path, derived from the same constant the
         * repository uses (TASK-643): a pure version bump with no entry
         * changes must not leave the tests validating the previous
         * release's file.
         */
        private val BUNDLED_INDEX_PATH =
            "src/main/assets/" + ExternalCatalogRepository.BUNDLED_INDEX
    }

    @Test
    fun `query matches language code exactly and by prefix`() {
        assertTrue(ExternalCatalog.matchesQuery("Arabic Whisper", listOf("ar"), "ar"))
        assertTrue(ExternalCatalog.matchesQuery("Portuguese", listOf("pt-BR"), "pt"))
        assertTrue(ExternalCatalog.matchesQuery("Portuguese", listOf("pt-BR"), "pt-br"))
        assertFalse(ExternalCatalog.matchesQuery("Arabic Whisper", listOf("ar"), "ru"))
    }

    @Test
    fun `query matches name case-insensitively`() {
        assertTrue(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "arabic"))
        assertTrue(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "Whisper"))
        assertFalse(ExternalCatalog.matchesQuery("Whisper Large v3 Turbo Arabic", listOf("ar"), "gigaam"))
    }

    @Test
    fun `name tokens match whole words or their prefixes, never inner substrings`() {
        // "ar" sits inside "Canary" but is not a word or word prefix there; it
        // must not surface Canary names when the user is filtering by language.
        assertFalse(ExternalCatalog.matchesQuery("Canary Flash 180M (German)", listOf("de"), "ar"))
        // "ry" and "ca" are inner substrings of "Canary" as well
        assertFalse(ExternalCatalog.matchesQuery("Canary Flash 180M (German)", listOf("de"), "ry"))
        // word prefixes do match, at any token length
        assertTrue(ExternalCatalog.matchesQuery("Canary Flash 180M (German)", listOf("de"), "can"))
        assertTrue(ExternalCatalog.matchesQuery("Canary Flash 180M (German)", listOf("de"), "german"))
        // short words of a name stay searchable ("v3" in three catalog names)
        assertTrue(ExternalCatalog.matchesQuery("Whisper v3 Turbo German", listOf("de"), "v3"))
    }

    @Test
    fun `multi-token query requires every token to match`() {
        assertTrue(ExternalCatalog.matchesQuery("Whisper Arabic", listOf("ar"), "whisper ar"))
        assertFalse(ExternalCatalog.matchesQuery("Whisper Arabic", listOf("ar"), "whisper ru"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(ExternalCatalog.matchesQuery("Anything", emptyList(), ""))
        assertTrue(ExternalCatalog.matchesQuery("Anything", emptyList(), "   "))
    }

    @Test
    fun `index parses entries with name languages family and entry url`() {
        val index = """
            {"entries": [
              {"name": "Whisper Arabic", "languages": ["ar"], "family": "WHISPER",
               "entryUrl": "https://example.com/arabic.json"}
            ]}
        """.trimIndent()
        val entries = ExternalCatalog.parseIndex(index)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Whisper Arabic", e.name)
        assertEquals(listOf("ar"), e.languages)
        assertEquals(ModelFamily.WHISPER, e.family)
        assertEquals("https://example.com/arabic.json", e.entryUrl)
    }

    @Test
    fun `index entry without family defaults to transducer and malformed entries are skipped`() {
        val index = """
            {"entries": [
              {"name": "GigaAM", "languages": ["ru"], "entryUrl": "https://example.com/ru.json"},
              {"name": "broken"}
            ]}
        """.trimIndent()
        val entries = ExternalCatalog.parseIndex(index)
        assertEquals(1, entries.size)
        assertEquals(ModelFamily.TRANSDUCER, entries[0].family)
        assertEquals("ru", entries[0].languages.single())
    }

    @Test
    fun `unknown family string is skipped rather than crashing the dialog`() {
        val index = """
            {"entries": [
              {"name": "X", "languages": ["en"], "family": "FIRERED", "entryUrl": "https://example.com/x.json"}
            ]}
        """.trimIndent()
        assertEquals(0, ExternalCatalog.parseIndex(index).size)
    }

    @Test
    fun `filter returns entries matching the query in input order`() {
        val entries = listOf(
            ExternalCatalog.CatalogEntry("Whisper Arabic", listOf("ar"), "u1", ModelFamily.WHISPER),
            ExternalCatalog.CatalogEntry("GigaAM v3", listOf("ru"), "u2", ModelFamily.TRANSDUCER),
        )
        assertEquals(listOf("u1"), ExternalCatalog.filter(entries, "ar").map { it.entryUrl })
        assertEquals(entries, ExternalCatalog.filter(entries, ""))
        assertEquals(emptyList<ExternalCatalog.CatalogEntry>(), ExternalCatalog.filter(entries, "zh"))
    }

    @Test
    fun `partitionByLanguage puts declaring entries first and the rest under other`() {
        val entries = listOf(
            ExternalCatalog.CatalogEntry("Swiss German", listOf("de", "gsw"), "u1", ModelFamily.WHISPER),
            ExternalCatalog.CatalogEntry("Russian", listOf("ru"), "u2", ModelFamily.TRANSDUCER),
        )
        // regional prefix match: de-CH declared, de selected
        val regional = listOf(
            ExternalCatalog.CatalogEntry("Regional", listOf("de-CH"), "u3", ModelFamily.WHISPER))
        val (m1, o1) = ExternalCatalog.partitionByLanguage(entries, "de")
        assertEquals(listOf("u1"), m1.map { it.entryUrl })
        assertEquals(listOf("u2"), o1.map { it.entryUrl })
        assertEquals(listOf("u3"), ExternalCatalog.partitionByLanguage(regional, "de").first.map { it.entryUrl })
        // blank language = everything matches, nothing under other
        val (all, none) = ExternalCatalog.partitionByLanguage(entries, "")
        assertEquals(2, all.size)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `bundled asset index carries the sherpa-compatible arabic mirror entry`() {
        // The OpenVoiceOS optimum export was replaced by the validated mirror
        // pantinor/whisper-arabic-dialectal-sherpa (TASK-332: desktop-verified
        // transcripts on 6 dialectal samples, 2026-08-19). The entry must parse
        // and surface via both name and language-code search.
        val text = java.io.File(BUNDLED_INDEX_PATH).readText()
        val entries = ExternalCatalog.parseIndex(text)
        // arabic + russian-small + spanish streaming + german streaming (TASK-366/368)
        // + swiss german whisper (TASK-397, Flurin17 re-export)
        // + german whisper (TASK-404, primeline re-export)
        // + canary flash per language en/de/es/fr (TASK-408, renamed from
        // "NeMo Flash" to NVIDIA's canonical family naming)
        // + whisper tiny multilingual (TASK-475, low-RAM) + sense voice
        // small multilingual (TASK-476, zh/en/yue/ja/ko)
        // + shenava persian transducer (TASK-550, desktop-validated on FLEURS fa)
        // + orukeet multilingual parakeet fine-tune (2026-09-20; the Italian
        //   FLEURS gain is the PUBLISHER's self-report - our eval references
        //   are Parakeet pseudo-refs, not ground truth)
        // + omnilingual 300M CTC multilingual (TASK-635/643: version-scoped
        //   index; the unsuffixed index.json stays frozen at 14 for <=1.13.x)
        assertEquals(15, entries.size)

        // TASK-635/643: the omnilingual entry ships in the VERSIONED index
        // (the bundled asset); the unsuffixed index.json is the frozen legacy
        // set for installed apps <=1.13.x.
        val omnilingual = ExternalCatalog.filter(entries, "omnilingual")
        assertEquals(1, omnilingual.size)
        assertEquals(ModelFamily.CTC, omnilingual[0].family)
        // The entry FILE carries the modelType the index cannot: pin it so a
        // drift fails here instead of at install time on a user device.
        val omnilingualJson = java.io.File(
            "src/main/assets/external-catalog/omnilingual-300m.json").readText()
        val omnilingualEntry = org.json.JSONObject(omnilingualJson)
        assertTrue(omnilingualEntry.getString("name").startsWith("Omnilingual ASR 300M"))
        assertEquals("CTC", omnilingualEntry.getString("family"))
        assertEquals("omnilingual_ctc", omnilingualEntry.getString("modelType"))
        assertEquals(2, omnilingualEntry.getJSONArray("files").length())

        val sense = ExternalCatalog.filter(entries, "sense")
        assertEquals(1, sense.size)
        assertEquals(ModelFamily.SENSE_VOICE, sense[0].family)
        // the reporter's Asian-language path: zh finds it via the language code
        assertEquals(sense, ExternalCatalog.filter(entries, "zh"))
        val arabic = ExternalCatalog.filter(entries, "arabic")
        assertEquals(1, arabic.size)
        val byCode = ExternalCatalog.filter(entries, "ar")
        assertEquals(arabic, byCode)
        assertEquals(ModelFamily.WHISPER, arabic[0].family)
        // TASK-550: the fa code surfaces the Persian entry alone. The matcher
        // prefix-matches name WORDS and language codes: no other entry may
        // declare fa, and no name word here starts with "ar" or "fa"
        // (matching "ar" would break the arabic isolation above).
        val persian = ExternalCatalog.filter(entries, "fa")
        assertEquals(1, persian.size)
        assertEquals(ModelFamily.TRANSDUCER, persian[0].family)
        // "ry" is an inner substring of "Canary": word matching keeps it silent
        assertTrue(ExternalCatalog.filter(entries, "ry").isEmpty())
        // a real name word still finds the four flash entries...
        assertEquals(4, ExternalCatalog.filter(entries, "canary").size)
        // ...and the "de" code surfaces every German-capable entry via
        // languages (TASK-596: orukeet now declares its full 24-language
        // set, so it joins the German results)
        assertEquals(5, ExternalCatalog.filter(entries, "de").size)
    }

    @Test
    fun `bundled index is alphabetically sorted by display name`() {
        // Only OUR curated file is held to the rule: parseIndex deliberately
        // keeps input order so a custom/remote catalog may ship unsorted.
        val text = java.io.File(BUNDLED_INDEX_PATH).readText()
        val names = ExternalCatalog.parseIndex(text).map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test
    fun `frozen legacy index for installed base stays parseable at its frozen count`() {
        // TASK-643: index.json is the live remote index for every installed
        // app <=1.13.x. It must stay parseable, at 14 entries, and a strict
        // subset of the versioned index (a compatible fix may edit an entry,
        // nothing may be added or broken).
        val legacy = ExternalCatalog.parseIndex(
            java.io.File("src/main/assets/external-catalog/index.json").readText())
        assertEquals(14, legacy.size)
        val versioned = ExternalCatalog.parseIndex(java.io.File(BUNDLED_INDEX_PATH).readText())
        val versionedByName = versioned.associateBy { it.name }
        // Shared entries must stay in SYNC (the runbook's compatible fixes
        // apply to every live file): pin entryUrl equality, the field a
        // mirror fix touches, not just name presence.
        legacy.forEach { entry ->
            val match = versionedByName[entry.name]
            assertNotNull("legacy entry missing from the versioned index: ${entry.name}", match)
            assertEquals("entryUrl drifted between legacy and versioned: ${entry.name}",
                entry.entryUrl, match!!.entryUrl)
        }
        // Exactly one versioned index ships: the running build reads only its
        // own version's file; stale ones are dead APK weight.
        val versionedFiles = java.io.File("src/main/assets/external-catalog")
            .listFiles { f -> f.name.matches(Regex("index-.*\\.json")) }
        assertEquals(1, versionedFiles?.size)
    }

    @Test
    fun `every catalog index entry is listed in the model catalog doc`() {
        // Sync contract: adding an index entry requires its exact name to
        // appear in docs/model-catalog.md, so the user-facing model list
        // cannot drift from the catalog the app serves.
        val text = java.io.File(BUNDLED_INDEX_PATH).readText()
        val doc = java.io.File("../docs/model-catalog.md").readText()
        ExternalCatalog.parseIndex(text).forEach {
            assertTrue("docs/model-catalog.md community table is missing ${it.name}", doc.contains(it.name))
        }
    }
}
