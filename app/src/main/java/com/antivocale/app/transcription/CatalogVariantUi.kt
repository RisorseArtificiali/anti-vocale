package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogStringKeys
import com.antivocale.app.data.catalog.CatalogVariant

/**
 * TASK-767: the ONE owner of countPlaceholder rendering policy. A display
 * that asks for the count gets the entry's language count as its format
 * argument; every other display resolves plainly. The extensions live on
 * the ENTRY (not the display) so a display/entry mismatch pair cannot be
 * written; render sites pass the result to countAwareStringResource /
 * countAwareString, which keeps the forgotten-arg bug unrepresentable.
 */
fun CatalogEntry.titleCountArg(): Int? =
    (display as? CatalogDisplay.Resource)?.takeIf { it.countPlaceholder }?.let { languages.size }

fun CatalogEntry.descriptionCountArg(): Int? =
    (description as? CatalogDisplay.Resource)?.takeIf { it.countPlaceholder }?.let { languages.size }

/** The variant-scoped member of the same family: a countPlaceholder
 *  description counts the variant's OWN language set (the narrower truth
 *  a variant can declare). */
fun ModelVariant.descriptionFormatArg(): Int? =
    if (countPlaceholder) supportedLanguageCodes.size else null

/** The variant-scoped title twin (an inherited or own title may format). */
fun ModelVariant.titleFormatArg(): Int? =
    if (titleCountPlaceholder) supportedLanguageCodes.size else null


/**
 * Catalog-driven variant metadata for a built-in model (the consolidated
 * replacement for the deleted per-model variant enums).
 *
 * Every field comes from the bundled catalog: titles/descriptions are localized
 * resource keys resolved via [CatalogStringKeys], sizes and dir names are the
 * variant's own, and language support is the entry's effective language list.
 * Implements [ModelVariant] so the shared UI components (cards, overlays, info
 * provider) keep working unchanged.
 */
data class CatalogVariantUi(
    val backendId: String,
    val variantName: String,
    override val dirName: String,
    override val titleResId: Int,
    override val descriptionResId: Int,
    override val estimatedSizeMB: Long,
    override val supportedLanguageCodes: Set<String>,
    override val countPlaceholder: Boolean = false,
    override val titleCountPlaceholder: Boolean = false,
    val badgeKey: String? = null,
) : ModelVariant {

    companion object {
        private fun entry(entryId: String): CatalogEntry =
            BundledCatalog.byId(entryId)
                ?: throw IllegalStateException("bundled catalog is missing entry '$entryId'")

        private fun resolveDisplay(d: CatalogDisplay?, what: String): Int {
            val key = (d as? CatalogDisplay.Resource)?.key
                ?: throw IllegalArgumentException("catalog $what must declare a localized resource string")
            return CatalogStringKeys.resolve(key)
        }

        private fun fromVariant(entry: CatalogEntry, variant: CatalogVariant): CatalogVariantUi =
            CatalogVariantUi(
                backendId = entry.id,
                variantName = variant.name,
                dirName = variant.dirName,
                titleResId = resolveDisplay(variant.title ?: entry.display, "${entry.id}/${variant.name} title"),
                descriptionResId = resolveDisplay(variant.description ?: entry.description, "${entry.id}/${variant.name} description"),
                countPlaceholder = ((variant.description ?: entry.description) as? CatalogDisplay.Resource)?.countPlaceholder == true,
                // TASK-767 review: symmetric with the description chain -
                // the variant's OWN title when declared, the entry display
                // otherwise; either may format the count.
                titleCountPlaceholder = ((variant.title ?: entry.display)
                    as? CatalogDisplay.Resource)?.countPlaceholder == true,
                estimatedSizeMB = variant.estimatedSizeMB,
                supportedLanguageCodes = entry.languagesFor(variant).toSet(),
                badgeKey = variant.badgeKey,
            )

        /** All variants of a built-in entry, in catalog order. */
        fun forEntry(entryId: String): List<CatalogVariantUi> {
            val e = entry(entryId)
            return e.variants.map { fromVariant(e, it) }
        }

        /** A single variant ([variantName] null = the entry's default variant). */
        fun of(entryId: String, variantName: String? = null): CatalogVariantUi {
            val e = entry(entryId)
            return fromVariant(e, variantName?.let(e::variant) ?: e.defaultVariant)
        }
    }
}