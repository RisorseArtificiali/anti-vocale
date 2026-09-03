package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogVariant
import java.util.Locale

/**
 * TASK-434: maps the saved transcription-language preference to the language
 * string a backend receives.
 *
 * The preference has two sentinels: "system" (the untouched default: follow the
 * app/UI locale when the loaded variant opts in via its catalog
 * `preferUiLanguage` flag and supports it) and "auto" (an explicit choice:
 * true model-side detection).
 * A concrete code is pinned and passes through. Single-language variants are
 * NOT resolved here: [SherpaBackend.forcedLanguage] forces them (Whisper
 * Distil-IT → "it") AFTER this mapping, so it keeps winning over anything the
 * locale-following default resolves.
 *
 * Pure Kotlin, no Android imports: the locale enters as a parameter so the
 * mapping is JVM-testable (AudioDurationPolicy pattern).
 */
object TranscriptionLanguagePolicy {

    /** Preference sentinel: follow the app locale (the untouched default). */
    const val PREF_SYSTEM = "system"

    /** Preference sentinel: explicit model-side auto-detection. */
    const val PREF_AUTO = "auto"

    /**
     * Offline passLanguage entries (Whisper): "auto" → "" (model-side
     * detection), a pinned code passes through, and the "system" default (or a
     * blank legacy value) follows the app locale ONLY on variants flagged
     * `preferUiLanguage` (CatalogVariant) whose language list contains it, else
     * falls back to "" (auto). A region locale matches on its language part
     * ("pt-BR" → "pt"), the shape every catalog language list uses.
     */
    fun resolveOffline(
        preference: String,
        preferUiLanguage: Boolean,
        variantLanguages: List<String>,
        uiLocale: Locale?,
    ): String = when {
        preference == PREF_AUTO -> ""
        preference == PREF_SYSTEM || preference.isBlank() ->
            if (preferUiLanguage && uiLocale != null && uiLocale.language in variantLanguages) {
                uiLocale.language
            } else {
                ""
            }
        else -> preference
    }

    /**
     * Online languageOption entries (Nemotron): sentinels and a blank value map
     * to "auto" (the per-stream auto-detect option), a concrete code passes.
     */
    fun resolveStream(preference: String): String =
        if (preference.isBlank() || preference == PREF_SYSTEM) PREF_AUTO else preference

    /**
     * The per-entry language wiring, shared by every config-builder site (the
     * orchestrator load path and the Model benchmark, so the benchmark always
     * measures what transcription would actually run with): languageOption
     * (online Nemotron) resolves per-stream, passLanguage (offline Whisper)
     * resolves the offline mapping for the installed [variant], everything
     * else gets "".
     */
    fun resolveForEntry(
        entry: CatalogEntry,
        variant: CatalogVariant,
        preference: String,
        uiLocale: Locale?,
    ): String = when {
        entry.flags.languageOption -> resolveStream(preference)
        entry.flags.passLanguage -> resolveOffline(
            preference = preference,
            preferUiLanguage = variant.preferUiLanguage,
            variantLanguages = entry.languagesFor(variant),
            uiLocale = uiLocale,
        )
        else -> ""
    }
}
