package com.antivocale.app.ui

import com.antivocale.app.util.DeviceCompatibility
import com.antivocale.app.data.ModelAccuracy
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.transcription.CatalogVariantUi

/**
 * TASK-1021: the pure core of the Models tab's "Suggeriti per il tuo
 * telefono" filter. The device axis is the shipped [DeviceCompatibility.RamTier]
 * (the tier line's own derivation); the size axis is the variant's declared
 * estimate; the ordering is the maintainer's rule: once a model is inside
 * the device's size budget, what decides its position is (1) whether it
 * covers the user's language and (2) its measured quality, with size only
 * as the tiebreaker. A size-sorted suggestion list would lead with a
 * Hebrew zipformer for an Italian user (the maintainer's example), which
 * is exactly what this ordering exists to prevent.
 *
 * The quality axis reads [ModelAccuracy] internally but NEVER surfaces it:
 * rows in the model list must not carry accuracy (the TASK-658 constraint,
 * PerformanceStatsDialog is the one surface). The quality lives in the
 * ORDER; the only per-card tag this feature adds is the covered language.
 *
 * Advisory only, always: no predicate here refuses anything; callers hide
 * or reveal suggestions and that is the whole effect (the TASK-631
 * principle). HIGH-band devices get a null budget upstream and no filter
 * UI at all.
 */
object DeviceFitSuggestion {

    /**
     * The advisory size budget per tier, in MB (the catalog's declared
     * estimate unit). CONSTRAINED keeps the comfortable set (Parakeet
     * compact 640 in, the full 862 out); STANDARD draws the line under the
     * 1GB-class multilinguals (Qwen3 938, Distil IT 984 in; Whisper Medium
     * and the Gemma lane out). Named here so the bands and the budgets read
     * as one table; HIGH is absent because the filter does not exist there.
     */
    val budgetMb: Map<DeviceCompatibility.RamTier, Long> = mapOf(
        DeviceCompatibility.RamTier.CONSTRAINED to 750L,
        DeviceCompatibility.RamTier.STANDARD to 1500L,
    )

    /** Whether the variant fits the tier's budget; true on a tier without one (HIGH, unknown). */
    fun fits(sizeMb: Long, tier: DeviceCompatibility.RamTier): Boolean =
        fits(sizeMb, budgetMb[tier])

    /**
     * The one budget predicate every site shares (eligibility, the visible
     * cut, the badge): a null budget fits everything, which is the advisory
     * contract (HIGH band, filter off, no badge).
     */
    fun fits(sizeMb: Long, budgetMb: Long?): Boolean = budgetMb == null || sizeMb <= budgetMb

    /**
     * Locale codes the platform still reports in legacy form (Locale.forLanguageTag
     * keeps these); the catalog and the measurements use the modern codes, so
     * an unnormalized "iw" never matches a "he" variant.
     */
    private val legacyLocaleCodes = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /** The ordering language: the explicit filter choice or the device locale, modern-coded. */
    fun orderingLanguage(rawLanguage: String): String =
        legacyLocaleCodes[rawLanguage] ?: rawLanguage

    /**
     * The quality number for ordering: the model's best (lowest) WER for
     * the user's language; null when unmeasured (unmeasured sorts after
     * measured, and among unmeasured the size breaks the tie).
     */
    fun qualityWer(
        modelId: String,
        variantDirName: String,
        language: String,
        measurements: List<ModelAccuracy.Measurement>,
    ): Double? =
        measurements
            .filter { it.metric == "WER" && it.language == language && it.matches(modelId, variantDirName) }
            .minOfOrNull { it.value }

    /**
     * Orders models that are already inside the budget: user-language
     * coverage first, then the best WER for that language, then size; the
     * models without the language follow, by size. Stable on equal keys so
     * the catalog's own order survives as the last resort. The [wer]
     * selector is the caller's to compute: decorate once before sorting
     * rather than deriving it inside the comparator.
     */
    fun <T> order(
        models: List<T>,
        userLanguage: String,
        sizeMb: (T) -> Long,
        languages: (T) -> Set<String>,
        wer: (T) -> Double?,
    ): List<T> =
        models.sortedWith(
            compareBy(
                { userLanguage !in languages(it) },
                { wer(it) ?: Double.MAX_VALUE },
                { sizeMb(it) },
            )
        )

    /**
     * Entry-level convenience for the tab. [eligible] is the caller's own
     * two-gate variant list (the language filter AND the budget cut - the
     * same gates the visible cards apply), computed ONCE per entry; the
     * decoration pass resolves each entry's size, languages, and quality
     * key once so the sort's selectors only read fields. An entry stays
     * when ANY variant is eligible, and orders by its best eligible
     * variant (the suggestion rule applied to the variant each entry
     * would actually surface first).
     */
    fun orderEntries(
        entries: List<CatalogEntry>,
        userLanguage: String,
        measurements: List<ModelAccuracy.Measurement>,
        eligible: (CatalogEntry) -> List<CatalogVariantUi>,
    ): List<CatalogEntry> {
        data class Decorated(
            val entry: CatalogEntry,
            val sizeMb: Long,
            val languages: Set<String>,
            val wer: Double?,
        )
        return order(
            entries.mapNotNull { entry ->
                val variants = eligible(entry)
                if (variants.isEmpty()) return@mapNotNull null
                // The quality key is the entry's best MEASURED eligible
                // variant (the review finding: minBy-size made an entry
                // whose smallest variant is unmeasured sort as unmeasured,
                // burying its measured flagship); size only breaks ties and
                // speaks for the entry when nothing is measured.
                val measured: Pair<CatalogVariantUi, Double>? = variants
                    .mapNotNull { v -> qualityWer(entry.id, v.dirName, userLanguage, measurements)?.let { v to it } }
                    .minByOrNull { it.second }
                // variants is non-empty (guarded above), so minByOrNull
                // cannot yield null here; the assertion only satisfies types.
                val spokenFor = measured?.first
                    ?: variants.minByOrNull { it.estimatedSizeMB }!!
                Decorated(
                    entry = entry,
                    sizeMb = spokenFor.estimatedSizeMB,
                    languages = variants.flatMap { it.supportedLanguageCodes }.toSet(),
                    wer = measured?.second,
                )
            },
            userLanguage = userLanguage,
            sizeMb = { it.sizeMb },
            languages = { it.languages },
            wer = { it.wer },
        ).map { it.entry }
    }
}
