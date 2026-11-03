package com.antivocale.app.transcription

/**
 * Common interface for transcription model variants.
 *
 * [CatalogVariantUi] (catalog-driven) and [ModelDownloader.ModelVariant] (Gemma)
 * implement this, enabling generic UI components like [ModelVariantCard] to render
 * any variant.
 */
interface ModelVariant {
    val titleResId: Int
    val descriptionResId: Int
    /**
     * TASK-946: the title's format arguments in the display's slot order
     * (see PlaceholderSlot; the ordinal order is the format order). Derived
     * by the policy owner (CatalogVariantUi) from the catalog, never
     * literals; empty = the title resolves plainly.
     */
    val titleFormatArgs: List<Any> get() = emptyList()
    /** The description twin of [titleFormatArgs]. */
    val descriptionFormatArgs: List<Any> get() = emptyList()
    val dirName: String
    val estimatedSizeMB: Long
    val supportedLanguageCodes: Set<String> get() = emptySet()
}
