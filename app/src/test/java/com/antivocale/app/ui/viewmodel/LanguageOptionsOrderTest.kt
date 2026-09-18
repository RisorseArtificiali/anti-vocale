package com.antivocale.app.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * TASK-353: the language selectors pin their sentinel entry first and sort the
 * rest with a Collator for the ACTIVE locale (alphabetical order is
 * locale-dependent; the system language picker does the same).
 */
class LanguageOptionsOrderTest {

    @Test
    fun `the picker offers every locale the app ships`() {
        // TASK-561: appLanguageCodes is hand-maintained while the res tree
        // grows by directory; iw/pl/tr/uk shipped for four releases without
        // ever reaching the picker. values-<lang>[-r<REGION>] dirs and picker
        // codes must stay in 1:1 correspondence (the default values/ dir is
        // the untranslated base, not a picker entry). Hebrew ships in its
        // legacy values-iw dir name but the picker keys the canonical "he"
        // (Android 15+ canonicalizes the tag; see appLanguageCodes).
        // Dual probe: module-relative and repo-root-relative, the convention
        // the sibling filesystem tests use so root-CWD launches also work.
        val res = File("src/main/res").takeIf { it.isDirectory }
            ?: File("app/src/main/res").takeIf { it.isDirectory }
        assertTrue("res dir not found (CWD=${File(".").absolutePath})", res != null)
        val dirCodes = res!!.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .map { dir -> dir.name.removePrefix("values-") }
            // Only locale-shaped dirs vote: <lang> or <lang>-r<REGION>. A
            // future values-night/values-v27 must not fail this guard.
            .filter { tag -> tag.matches(Regex("[a-z]{2,3}(-r[A-Z]{2})?")) }
            .map { tag ->
                if (tag.length == 2 && tag == "iw") "he"
                else tag.split("-").joinToString("-") { part ->
                    if (part.startsWith("r") && part.length == 3) part.drop(1).uppercase()
                    else part
                }
            }
            .sorted()
        // values/ (no suffix) is the default English locale: it has no
        // directory of its own but IS a picker entry.
        val shipped = (dirCodes + "en").sorted()
        val picker = languageOptionsFor(Locale.ENGLISH).drop(1).map { it.code }.sorted()
        assertEquals(shipped, picker)
    }

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
        assertEquals(14, languageOptionsFor(Locale.ENGLISH).size)
        // TASK-547 (review round 2): the phone sentinel is offered only when
        // its resolved code is IN the offered set (AC#2: hidden otherwise);
        // "auto" + every offered entry always survive the picker build.
        val offered = setOf("zh", "de", "it")
        assertEquals(
            2 + offered.size,
            transcriptionPickerFor(offered, Locale.ENGLISH, phoneLanguage = "de").options.size)
        // Phone locale outside the offered set: hidden, auto + codes only.
        assertEquals(
            1 + offered.size,
            transcriptionPickerFor(offered, Locale.ENGLISH, phoneLanguage = "ru").options.size)
        // Unreadable locale: hidden likewise.
        assertEquals(
            1 + offered.size,
            transcriptionPickerFor(offered, Locale.ENGLISH, phoneLanguage = null).options.size)
        // No conditioning: only the auto sentinel, no phone
        assertEquals(1, transcriptionPickerFor(emptySet(), Locale.ENGLISH).options.size)
    }
}
