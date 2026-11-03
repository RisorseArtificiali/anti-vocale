package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogStringKeys
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.catalog.PlaceholderSlot
import com.antivocale.app.data.catalog.formatArgs

/**
 * TASK-946: the ONE owner of placeholder rendering policy. A display's slot
 * list is mapped to format arguments in slot order (PlaceholderSlot's
 * ordinal order is the format order), so the args a render site passes
 * always match the string's positional specifiers. The extensions live on
 * the ENTRY (not the display) so a display/entry mismatch pair cannot be
 * written; render sites pass the result to placeholderAwareStringResource /
 * placeholderAwareString, and an empty list resolves the string plainly.
 */
fun CatalogEntry.titleFormatArgs(): List<Any> = displayFormatArgs(display)

fun CatalogEntry.descriptionFormatArgs(): List<Any> = displayFormatArgs(description)

private fun CatalogEntry.displayFormatArgs(display: CatalogDisplay?): List<Any> {
    val slots = (display as? CatalogDisplay.Resource)?.placeholders ?: return emptyList()
    return slots.formatArgs(defaultVariant.estimatedSizeMB, languages.size)
}


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
    /**
     * The resolved display's slot lists, private because the consumed
     * abstraction is the derived formatArgs (a consumer reading the raw
     * slots could rebuild a divergent slot-to-value mapping, the class
     * TASK-946 closed).
     */
    private val descriptionPlaceholders: List<PlaceholderSlot> = emptyList(),
    private val titlePlaceholders: List<PlaceholderSlot> = emptyList(),
    val badgeKey: String? = null,
    /** TASK-365: the repo is HuggingFace-gated (the add-token warning row). */
    val requiresAuth: Boolean = false,
    /**
     * TASK-365: the variant's file name when it is a single-file layout
     * (the litertlm entry: the saved path is the FILE, so active-state
     * matching extends to it); null for multi-file (sherpa) variants.
     */
    val singleFileName: String? = null,
) : ModelVariant {

    /**
     * The variant-scoped members of the placeholder policy: each slot reads
     * the variant's OWN truth (its size; its language set, the narrower
     * truth a variant can declare).
     */
    override val descriptionFormatArgs: List<Any>
        get() = descriptionPlaceholders.formatArgs(estimatedSizeMB, supportedLanguageCodes.size)

    /** The title twin (an inherited or own title may declare slots). */
    override val titleFormatArgs: List<Any>
        get() = titlePlaceholders.formatArgs(estimatedSizeMB, supportedLanguageCodes.size)


    companion object {
        private fun entry(entryId: String): CatalogEntry =
            BundledCatalog.byId(entryId)
                ?: throw IllegalStateException("bundled catalog is missing entry '$entryId'")

        private fun resolveDisplay(d: CatalogDisplay?, what: String): Int {
            val key = (d as? CatalogDisplay.Resource)?.key
                ?: throw IllegalArgumentException("catalog $what must declare a localized resource string")
            return CatalogStringKeys.resolve(key)
        }

        private fun fromVariant(entry: CatalogEntry, variant: CatalogVariant): CatalogVariantUi {
            // The inherited/own chains, resolved once: the variant's OWN
            // display when declared, the entry's otherwise.
            val titleDisplay = variant.title ?: entry.display
            val descriptionDisplay = variant.description ?: entry.description
            return CatalogVariantUi(
                backendId = entry.id,
                variantName = variant.name,
                dirName = variant.dirName,
                titleResId = resolveDisplay(titleDisplay, "${entry.id}/${variant.name} title"),
                descriptionResId = resolveDisplay(descriptionDisplay, "${entry.id}/${variant.name} description"),
                // The resolved display's ONE slot list (an entry-level
                // declaration covers the variants that inherit the entry
                // description, e.g. nemotron's size; the qwen3 count title).
                descriptionPlaceholders = (descriptionDisplay as? CatalogDisplay.Resource)
                    ?.placeholders ?: emptyList(),
                titlePlaceholders = (titleDisplay as? CatalogDisplay.Resource)
                    ?.placeholders ?: emptyList(),
                requiresAuth = variant.requiresAuth,
                singleFileName = variant.files.singleOrNull()?.name,
                estimatedSizeMB = variant.estimatedSizeMB,
                supportedLanguageCodes = entry.languagesFor(variant).toSet(),
                badgeKey = variant.badgeKey,
            )
        }

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