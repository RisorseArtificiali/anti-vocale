package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry
import java.io.File

/**
 * Maps the saved transcription-language preference to the language string a
 * backend receives, and derives the language codes the Settings picker offers
 * for the active backend.
 *
 * The preference has two sentinels: "system" (the untouched default) and
 * "auto" (an explicit choice). TASK-457 removed the app-locale pinning the
 * "system" default used to carry (a silent pin is a translation trap on
 * wrong-language audio, GH #84), so both sentinels now mean the same:
 * model-side detection. "system" survives only as the stored default so
 * existing installs keep resolving without a preference migration.
 * A concrete code is pinned and passes through. Single-language variants are
 * NOT resolved here: [SherpaBackend.forcedLanguage] forces them (Whisper
 * Distil-IT → "it") AFTER this mapping, so it keeps winning over anything
 * this policy resolves.
 *
 * Pure Kotlin, no Android imports, so every mapping is JVM-testable
 * (AudioDurationPolicy pattern).
 */
object TranscriptionLanguagePolicy {

    /** Preference sentinel: the untouched default; behaves exactly like [PREF_AUTO]. */
    const val PREF_SYSTEM = "system"

    /** Preference sentinel: explicit model-side auto-detection. */
    const val PREF_AUTO = "auto"

    /** Both sentinels and a blank legacy value mean "no pin" (model-side detection). */
    private fun isAutoDetect(preference: String): Boolean =
        preference == PREF_AUTO || preference == PREF_SYSTEM || preference.isBlank()

    /**
     * Offline passLanguage entries (Whisper): no pin maps to "" (model-side
     * detection); a pinned code passes through.
     */
    fun resolveOffline(preference: String): String =
        if (isAutoDetect(preference)) "" else preference

    /**
     * Online languageOption entries (Nemotron): no pin maps to "auto" (the
     * per-stream auto-detect option), a concrete code passes.
     */
    fun resolveStream(preference: String): String =
        if (isAutoDetect(preference)) PREF_AUTO else preference

    /**
     * The per-entry language wiring, shared by every config-builder site (the
     * orchestrator load path and the Model benchmark, so the benchmark always
     * measures what transcription would actually run with): languageOption
     * (online Nemotron) resolves per-stream, passLanguage (offline Whisper)
     * resolves the offline mapping, everything else gets "".
     */
    fun resolveForEntry(entry: CatalogEntry, preference: String): String = when {
        entry.flags.languageOption -> resolveStream(preference)
        entry.flags.passLanguage -> resolveOffline(preference)
        else -> ""
    }

    /**
     * TASK-458: the language codes the ACTIVE backend conditions on, driving
     * the Settings picker (GH #78). Dispatch mirrors [resolveForEntry]'s axis
     * (the catalog flags), not backend ids, so a future entry carrying either
     * flag is picked up without another branch here. passLanguage (Whisper)
     * offers the INSTALLED variant's catalog languages, derived from the saved
     * model path's directory name the same way the load path resolves the
     * variant (unknown directory names and a missing path fall back to the
     * default variant; a variant without its own list falls back to the
     * entry-level list). languageOption (Nemotron) offers the entry's own
     * languages (its prompt-dictionary set). Everything else (Parakeet,
     * Qwen3-ASR, GigaAM, Gemma, external imports, no backend) does not
     * condition on language: the empty set, which the UI renders as a
     * disabled section.
     */
    fun offeredLanguages(modelPath: String?, entry: CatalogEntry?): Set<String> = when {
        entry == null -> emptySet()
        entry.flags.languageOption -> entry.languagesFor(entry.defaultVariant).toSet()
        entry.flags.passLanguage -> {
            val variant = modelPath
                ?.let { entry.variantForDirName(File(it).name) }
                ?: entry.defaultVariant
            entry.languagesFor(variant).toSet()
        }
        else -> emptySet()
    }

    /**
     * TASK-458: how the picker renders the stored preference against the
     * offered set. A concrete pin the active model does not support is KEPT
     * (no destructive preference writes) and shown with a note; the dropdown
     * stays usable so the user can move to a supported language or Auto.
     */
    enum class PinState { NOT_PINNED, SUPPORTED_PIN, UNSUPPORTED_PIN }

    fun pinState(preference: String, offered: Set<String>): PinState = when {
        preference == PREF_AUTO || preference == PREF_SYSTEM || preference.isBlank() ->
            PinState.NOT_PINNED
        preference in offered -> PinState.SUPPORTED_PIN
        else -> PinState.UNSUPPORTED_PIN
    }
}
