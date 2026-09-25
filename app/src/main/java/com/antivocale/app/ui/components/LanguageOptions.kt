package com.antivocale.app.ui.components

import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.util.LanguageNames

// ---- TASK-353: locale-aware language option ordering ----
// Alphabetical order is locale-dependent, so the sort runs at READ time with a
// Collator for the active app locale (what the Android system language picker
// does, frameworks/opt/localepicker LocaleHelper). Display names come from the
// platform ICU/CLDR data via [LanguageNames] (native names: users find their
// language by its own name; see util/LanguageNames.kt). The sentinel entries
// (app language / auto-detect) stay pinned first; their labels are genuinely
// translatable and resolved from string resources at the UI layer, so their
// displayName here is an unused placeholder.
data class LanguageOption(val code: String, val displayName: String)

// Every locale the app actually ships (values-* dirs). Drift between this
// list and the res tree hides whole languages from the picker (iw/pl/tr/uk
// shipped for four releases before being noticed, TASK-561);
// LanguageOptionsOrderTest guards the two sides against each other. Hebrew
// uses the canonical "he": Android 15+ (targetSdk 35+) canonicalizes the
// legacy "iw" away in Locale.forLanguageTag, so a picker keyed "iw" would
// not round-trip through getCurrentLocaleCode after a restart; the res dir
// keeps its legacy values-iw name (that is what Android requires) and the
// test maps the two.
private val appLanguageCodes =
    listOf("de", "en", "es", "fa", "fr", "he", "hi", "it", "pl", "pt-BR", "ru", "tr", "uk")

private fun optionsFor(
    sentinelCodes: List<String>,
    codes: List<String>,
    locale: java.util.Locale,
): List<LanguageOption> {
    if (codes.isEmpty()) return sentinelCodes.map { LanguageOption(it, "") }
    val collator = java.text.Collator.getInstance(locale)
    val entries = codes
        .map { LanguageOption(it, LanguageNames.nativeLanguageName(it)) }
        .sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
    return sentinelCodes.map { LanguageOption(it, "") } + entries
}

internal fun languageOptionsFor(locale: java.util.Locale): List<LanguageOption> =
    optionsFor(listOf("system"), appLanguageCodes, locale)

/**
 * TASK-458: what the Transcription Language card renders for the active
 * backend. An empty offered set means "no language conditioning" and renders
 * the card disabled with an explanatory line; the offered codes become the
 * dropdown entries under the one "auto" sentinel (TASK-457: a stored "system"
 * default resolves identically to "auto", so it is no longer offered or
 * labeled separately).
 */
data class TranscriptionLanguagePicker(
    /** The "auto" sentinel plus the offered codes, sentinel-first, collated for the locale. */
    val options: List<LanguageOption>,
    /** The raw offered set; the unsupported-pin check compares the stored pin against it. */
    val offeredCodes: Set<String>,
) {
    /** False = the active model does not condition on language; the card renders disabled. */
    val conditioningAvailable: Boolean get() = offeredCodes.isNotEmpty()

    /** Just the code list, in menu order (no per-recomposition mapping at the call site). */
    val codes: List<String> get() = options.map { it.code }

    /** Label lookup for the dropdown rows and the current value (O(1), not a scan). */
    val optionByCode: Map<String, LanguageOption> by lazy { options.associateBy { it.code } }
}

internal fun transcriptionPickerFor(
    offered: Set<String>,
    locale: java.util.Locale,
    phoneLanguage: String? = null,
): TranscriptionLanguagePicker = TranscriptionLanguagePicker(
    // TASK-547 AC#2 (review round 2): "phone" (pin to the device locale) is
    // offered only where it would actually pin: the model conditions on
    // language AND the resolved phone language is in the offered set (a
    // distil-it with an English phone must not offer a pin that
    // forcedLanguage would silently override). Between Auto and the codes.
    options = optionsFor(
        if (phoneLanguage != null && phoneLanguage in offered) {
            listOf(TranscriptionLanguagePolicy.PREF_AUTO, TranscriptionLanguagePolicy.PREF_PHONE)
        } else {
            listOf(TranscriptionLanguagePolicy.PREF_AUTO)
        },
        offered.toList(), locale,
    ),
    offeredCodes = offered,
)
