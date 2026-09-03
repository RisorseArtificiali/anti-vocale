package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogFlags
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.catalog.CatalogDisplay
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-434 acceptance matrix for the transcription-language mapping
 * ([TranscriptionLanguagePolicy]): the untouched "system" default follows the
 * app locale ONLY on variants flagged preferUiLanguage (Whisper Small) and
 * ONLY when the locale's language is in the variant's language list; an
 * explicit "auto" keeps meaning true model-side detection; pinned codes pass
 * through; the online languageOption mapping is unchanged.
 */
class TranscriptionLanguagePolicyTest {

    private val smallLanguages = Language.WHISPER_MULTILINGUAL.toList()

    private fun resolveSmall(
        preference: String,
        locale: Locale? = Locale.ITALIAN,
        preferUiLanguage: Boolean = true,
    ) = TranscriptionLanguagePolicy.resolveOffline(
        preference = preference,
        preferUiLanguage = preferUiLanguage,
        variantLanguages = smallLanguages,
        uiLocale = locale,
    )

    // ---- Matrix 1: untouched default + supported locale + flagged variant ----

    @Test
    fun `system default follows the app locale on the flagged small variant`() {
        assertEquals("it", resolveSmall(TranscriptionLanguagePolicy.PREF_SYSTEM))
        // A blank legacy value behaves like the untouched default.
        assertEquals("it", resolveSmall(""))
    }

    // ---- Matrix 2: unflagged variants keep model-side detection on the default ----

    @Test
    fun `system default keeps auto-detection on unflagged variants`() {
        assertEquals(
            "",
            resolveSmall(TranscriptionLanguagePolicy.PREF_SYSTEM, preferUiLanguage = false)
        )
    }

    // ---- Matrix 3: explicit auto is true model-side detection, flagged or not ----

    @Test
    fun `explicit auto maps to empty string even on the flagged variant`() {
        assertEquals("", resolveSmall(TranscriptionLanguagePolicy.PREF_AUTO))
    }

    // ---- Matrix 5: unsupported locale falls back to auto ----

    @Test
    fun `locale outside the variant language list falls back to auto`() {
        assertEquals("", resolveSmall(TranscriptionLanguagePolicy.PREF_SYSTEM, locale = Locale("xx")))
    }

    // ---- Matrix 6: a pinned code passes through ----

    @Test
    fun `pinned language passes through`() {
        assertEquals("it", resolveSmall("it"))
        assertEquals("de", resolveSmall("de"))
        // A pinned code wins over the locale even when they disagree.
        assertEquals("en", resolveSmall("en", locale = Locale.ITALIAN))
    }

    // ---- Locale shapes ----

    @Test
    fun `region locale matches on its language part`() {
        // "pt-BR" is how the per-app language pref stores Portuguese (Brazil);
        // every catalog list carries plain "pt".
        assertEquals("pt", resolveSmall(TranscriptionLanguagePolicy.PREF_SYSTEM, locale = Locale("pt", "BR")))
    }

    @Test
    fun `null locale never locale-follows`() {
        assertEquals("", resolveSmall(TranscriptionLanguagePolicy.PREF_SYSTEM, locale = null))
    }

    // ---- Matrix 4 (policy half): what the orchestrator hands to single-language variants ----
    // The forcing itself is SherpaBackend.forcedLanguage (pinned in
    // TranscriptionOrchestratorLanguageTest against the real distil variant).

    @Test
    fun `policy resolves to empty or a code for the distil variant`() {
        val distilLanguages = Language.WHISPER_DISTIL_IT.toList()
        val resolved = TranscriptionLanguagePolicy.resolveOffline(
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            preferUiLanguage = false, // distil is not flagged
            variantLanguages = distilLanguages,
            uiLocale = Locale.ITALIAN,
        )
        // The orchestrator resolves "" here; forcedLanguage then forces "it".
        assertEquals("", resolved)
    }

    // ---- Matrix 7: the online languageOption (Nemotron) mapping is unchanged ----

    @Test
    fun `stream mapping sends auto for sentinels and passes codes through`() {
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(TranscriptionLanguagePolicy.PREF_SYSTEM))
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(""))
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(TranscriptionLanguagePolicy.PREF_AUTO))
        assertEquals("it", TranscriptionLanguagePolicy.resolveStream("it"))
    }

    // ---- The per-entry dispatch shared by the load path and the benchmark ----

    @Test
    fun `resolveForEntry dispatches on the catalog flags`() {
        fun entry(languageOption: Boolean, passLanguage: Boolean) = CatalogEntry(
            id = "test",
            runtime = if (languageOption) "online" else "offline",
            modelType = "",
            family = "TRANSDUCER",
            display = CatalogDisplay.Literal("test"),
            flags = CatalogFlags(languageOption = languageOption, passLanguage = passLanguage),
            variants = listOf(CatalogVariant(
                name = "v",
                dirName = "test-v",
                estimatedSizeMB = 0,
                preferUiLanguage = true,
                languages = listOf("it"),
                source = CatalogSource(kind = "url", template = "https://example.test/{file}"),
                files = emptyList(),
            )),
        )
        val variant = entry(false, true).variants.single()

        // languageOption (Nemotron): per-stream mapping.
        assertEquals(
            "auto",
            TranscriptionLanguagePolicy.resolveForEntry(
                entry(true, false), variant, TranscriptionLanguagePolicy.PREF_SYSTEM, null),
        )
        // passLanguage (Whisper): the offline mapping for the installed variant.
        assertEquals(
            "it",
            TranscriptionLanguagePolicy.resolveForEntry(
                entry(false, true), variant, TranscriptionLanguagePolicy.PREF_SYSTEM, Locale.ITALIAN),
        )
        // Neither flag (Parakeet/Qwen3-ASR/GigaAM): always "".
        assertEquals(
            "",
            TranscriptionLanguagePolicy.resolveForEntry(
                entry(false, false), variant, "it", Locale.ITALIAN),
        )
    }
}
