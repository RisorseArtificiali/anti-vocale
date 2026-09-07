package com.antivocale.app.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * TASK-353: the language selectors pin their sentinel entry first and sort the
 * rest with a Collator for the ACTIVE locale (alphabetical order is
 * locale-dependent; the system language picker does the same).
 */
class LanguageOptionsOrderTest {

    @Test
    fun `app language options pin system default first and collate the rest`() {
        val options = languageOptionsFor(Locale.ITALIAN)

        assertEquals("system", options.first().code)
        val rest = options.drop(1).map { it.displayName }
        val collator = java.text.Collator.getInstance(Locale.ITALIAN)
        assertEquals(rest.sortedWith { a, b -> collator.compare(a, b) }, rest)
    }

    @Test
    fun `transcription picker pins auto-detect first and collates the offered languages`() {
        val picker = transcriptionPickerFor(setOf("zh", "de", "it"), Locale.ENGLISH)

        // TASK-457: "auto" is the only sentinel; a stored "system" default
        // resolves identically, so it is no longer offered separately.
        assertEquals(listOf("auto"), picker.options.take(1).map { it.code })
        assertEquals(setOf("zh", "de", "it"), picker.offeredCodes)
        val rest = picker.options.drop(1).map { it.displayName }
        val collator = java.text.Collator.getInstance(Locale.ENGLISH)
        assertEquals(rest.sortedWith { a, b -> collator.compare(a, b) }, rest)
    }

    @Test
    fun `transcription picker with no offered languages renders disabled`() {
        // TASK-458: an empty offered set means "no language conditioning" and
        // the card renders disabled with the explanatory line.
        val picker = transcriptionPickerFor(emptySet(), Locale.ENGLISH)

        assertEquals(false, picker.conditioningAvailable)
        assertEquals(listOf("auto"), picker.options.map { it.code })
        assertTrue(picker.offeredCodes.isEmpty())
    }

    @Test
    fun `order follows the collator, not codepoint order`() {
        // Under a Latin collation, Español must sort with the other E names
        // (accent-insensitively: English < Español), NOT after the non-Latin
        // names the way raw codepoint comparison would place some entries.
        val rest = languageOptionsFor(Locale.ITALIAN).drop(1).map { it.displayName }
        assertTrue(
            "expected English before Español under Italian collation: $rest",
            rest.indexOf("English") < rest.indexOf("Español"),
        )
        // And the Latin block comes before the Devanagari/Cyrillic names under
        // this collation (codepoint order would scatter them).
        assertTrue(rest.indexOf("Italiano") < rest.indexOf("हिन्दी"))
        assertTrue(rest.indexOf("Русский") > rest.indexOf("Português (Brasil)") || rest.indexOf("Русский") < rest.indexOf("Deutsch"))
    }

    @Test
    fun `all entries survive sorting`() {
        assertEquals(9, languageOptionsFor(Locale.ENGLISH).size)
        // sentinel + every offered entry survive the picker build
        val offered = setOf("zh", "de", "it")
        assertEquals(1 + offered.size, transcriptionPickerFor(offered, Locale.ENGLISH).options.size)
    }
}
