package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.ModelCatalogJson
import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-796: the card-title flag derivation over the ONE curated table (the
 * same table the model-info chips read), pinned against the SHIPPED
 * catalog: the derivation the cards render must match the real
 * entry/variant language data, not a hand-copied expectation.
 */
class LanguageFlagsTitleTest {

    @Test
    fun `monolingual and few-language sets prefix the title`() {
        assertEquals("\uD83C\uDDEE\uD83C\uDDF9 Italiano", LanguageFlags.titled("Italiano", listOf("it")))
        assertEquals("\uD83C\uDDFA\uD83C\uDDE6 Ucraino", LanguageFlags.titled("Ucraino", listOf("uk")))
        // Duplicates collapse; order follows first appearance.
        assertEquals("\uD83C\uDDFA\uD83C\uDDE6\uD83C\uDDEA\uD83C\uDDF8 Titulo", LanguageFlags.titled("Titulo", listOf("uk", "es", "uk")))
    }

    @Test
    fun `pluricentric languages use the fixed regions`() {
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7 English", LanguageFlags.titled("English", listOf("en")))
        assertEquals("\uD83C\uDDF8\uD83C\uDDE6 Arabo", LanguageFlags.titled("Arabo", listOf("ar")))
    }

    @Test
    fun `empty set unchanged, too many languages carry the earth prefix`() {
        assertEquals("Multilingua", LanguageFlags.titled("Multilingua", emptyList()))
        assertEquals(
            "\uD83C\uDF0D Multilingua",
            LanguageFlags.titled("Multilingua", listOf("af", "am", "ar", "as")))
        // A PARTIAL mapping understates the set: mixed unknown+known never
        // flags (review round 2).
        assertEquals("Misto", LanguageFlags.titled("Misto", listOf("xxx", "it")))
    }

    @Test
    fun `the catalog multilingual marker and the swiss german code map`() {
        // The community catalog declares the literal marker (Whisper Tiny).
        assertEquals(
            "\uD83C\uDF0D Tiny",
            LanguageFlags.titled("Tiny", listOf("multilingual")))
        // gsw (ISO 639-3 Swiss German) rides de in the bilingual entry.
        assertEquals(
            "\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDE8\uD83C\uDDED Swiss",
            LanguageFlags.titled("Swiss", listOf("de", "gsw")))
    }

    @Test
    fun `the shipped catalog derives the expected card titles`() {
        val entries = ModelCatalogJson.parseCatalog(
            RepoFiles.readText("src/main/assets/models_catalog.json"))
        fun titleFor(id: String): String {
            val entry = entries.first { it.id == id }
            return LanguageFlags.titled("", entry.variants.flatMap(entry::languagesFor))
        }
        assertEquals("gigaam carries the Russian flag", "\uD83C\uDDF7\uD83C\uDDFA ", titleFor("gigaam"))
        assertEquals("parakeet carries the earth prefix",
            "\uD83C\uDF0D ", titleFor("sherpa-onnx"))
        assertEquals("whisper carries the earth prefix",
            "\uD83C\uDF0D ", titleFor("whisper"))
        assertEquals("qwen3 carries the earth prefix",
            "\uD83C\uDF0D ", titleFor("qwen3-asr"))
        assertEquals("nemotron carries the earth prefix",
            "\uD83C\uDF0D ", titleFor("nemotron-streaming"))
        // The DISTIL variant inside the whisper entry is monolingual Italian:
        // the variant ROW (not the entry) carries the flag.
        val whisper = entries.first { it.id == "whisper" }
        val distil = whisper.variant("distil-large-v3-it")!!
        assertEquals("\uD83C\uDDEE\uD83C\uDDF9 ", LanguageFlags.titled("", whisper.languagesFor(distil)))
    }
}
