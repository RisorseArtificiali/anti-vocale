package com.antivocale.app.ui.tabs

import androidx.annotation.StringRes
import com.antivocale.app.R

/**
 * GH #70 (v1 static seeds): per-language curated recommendations shown in the
 * Model tab's "For your language" section.
 *
 * Deliberately STATIC Kotlin data, not a catalog-index fetch: the seeds are
 * curated in this repo (Italian from the maintainer's own benchmark data,
 * German and Spanish from the community-catalog conversions), so the section
 * works offline on first run and changes only with an app release. The full
 * index-driven profile mechanism (banner + dropdown star, profiles carried in
 * the community index) is the approved long-term design in
 * docs/superpowers/specs/2026-08-30-model-tab-profiles-design.md and builds on
 * this data shape.
 *
 * Reference rules (enforced by [CuratedProfilesTest]):
 *  - [CuratedRecommendation.Bundled] references a bundled-catalog entry id
 *    (plus variant names when the pick is one variant of the entry, e.g. the
 *    Italian Distil inside the Whisper card); ids and variant names must exist
 *    in assets/models_catalog.json.
 *  - [CuratedRecommendation.Community] references a community-catalog index
 *    entry by its EXACT name in the versioned assets/external-catalog/index-<version>.json (TASK-643) (community
 *    entries have no id field); the curated card offers a one-tap import from
 *    that snapshot. The bundled asset is the resolution source on purpose: a
 *    user's catalog-URL override must not be able to change what the official
 *    curation recommends.
 *  - Every profile language is a lowercase ISO 639-1 code present in
 *    [com.antivocale.app.transcription.Language.FILTER_ENTRIES] (the same code
 *    conventions the language filter and TranscriptionLanguagePolicy use), so
 *    a profile is always reachable through the filter too.
 */
object CuratedProfiles {

    /** Where "Suggest a model for your language" points (the community-input thread). */
    const val SUGGESTION_ISSUE_URL = "https://github.com/RisorseArtificiali/anti-vocale/issues/70"

    /** One ranked recommendation inside a profile. */
    sealed interface Recommendation {

        /** The one-line localized reason rendered next to (above) the card. */
        @get:StringRes val whyResId: Int

        /**
         * A bundled catalog entry, rendered as the standard model card
         * (download/use/delete, same as the full list).
         */
        data class Bundled(
            /** Bundled-catalog entry id ("sherpa-onnx", "whisper", ...). */
            val entryId: String,
            /**
             * Restricts the card to these variants ("distil-large-v3-it");
             * null renders every variant of the entry.
             */
            val variantNames: List<String>? = null,
            @StringRes override val whyResId: Int,
        ) : Recommendation

        /**
         * A community-catalog entry (import flow), rendered as a compact card
         * with a one-tap import.
         */
        data class Community(
            /** Exact name of the entry in the bundled community index. */
            val catalogName: String,
            @StringRes override val whyResId: Int,
        ) : Recommendation
    }

    /** A language's curated page: language code + ranked recommendations. */
    data class Profile(
        val language: String,
        val recommendations: List<Recommendation>,
    )

    /**
     * The launch seeds. Ranking carries meaning: the first entry is the
     * quality pick for the language, then speed/compact/dialect alternates.
     */
    val PROFILES: List<Profile> = listOf(
        // Italian: Distil-large-v3-it measured 4.3% WER (best) vs Parakeet's
        // 5.4% at ~17x the speed (4-model Italian benchmark).
        Profile(
            language = "it",
            recommendations = listOf(
                Recommendation.Bundled(
                    entryId = "whisper",
                    variantNames = listOf("distil-large-v3-it"),
                    whyResId = R.string.curated_why_distil_it,
                ),
                Recommendation.Bundled(
                    entryId = "sherpa-onnx",
                    whyResId = R.string.curated_why_parakeet,
                ),
            ),
        ),
        // German: the primeline fine-tune is the accuracy pick; Parakeet the
        // fast multilingual backbone; Canary the compact option; the Flurin17
        // fine-tune covers Swiss German dialects.
        Profile(
            language = "de",
            recommendations = listOf(
                Recommendation.Community(
                    catalogName = "Whisper v3 Turbo German int8 (sherpa, primeline fine-tune)",
                    whyResId = R.string.curated_why_german_whisper,
                ),
                Recommendation.Bundled(
                    entryId = "sherpa-onnx",
                    whyResId = R.string.curated_why_parakeet,
                ),
                Recommendation.Community(
                    catalogName = "Canary Flash 180M (German)",
                    whyResId = R.string.curated_why_canary,
                ),
                Recommendation.Community(
                    catalogName = "Whisper v3 Turbo Swiss German int8 (sherpa, Flurin17 fine-tune)",
                    whyResId = R.string.curated_why_swiss_german,
                ),
            ),
        ),
        // Spanish: Parakeet as the balanced bundled default, then the two
        // community conversions (compact Canary, streaming Kroko).
        Profile(
            language = "es",
            recommendations = listOf(
                Recommendation.Bundled(
                    entryId = "sherpa-onnx",
                    whyResId = R.string.curated_why_parakeet,
                ),
                Recommendation.Community(
                    catalogName = "Canary Flash 180M (Spanish)",
                    whyResId = R.string.curated_why_canary,
                ),
                Recommendation.Community(
                    catalogName = "Kroko Community Zipformer Spanish (streaming, CC-BY-SA 4.0, Banafo / kroko.ai)",
                    whyResId = R.string.curated_why_kroko,
                ),
            ),
        ),
    )

    private val byLanguage: Map<String, Profile> = PROFILES.associateBy { it.language }

    /**
     * The profile for a language tag, or null when the language has no
     * curation. Accepts what the UI locale layer hands over ("IT", "de-CH",
     * "es_419"): the primary subtag is lowercased and region/script suffixes
     * are dropped, so a Swiss-German phone resolves to the German profile.
     */
    fun forLanguage(tag: String?): Profile? {
        if (tag.isNullOrBlank()) return null
        val primary = tag.trim().lowercase().substringBefore('-').substringBefore('_')
        return byLanguage[primary]
    }
}
