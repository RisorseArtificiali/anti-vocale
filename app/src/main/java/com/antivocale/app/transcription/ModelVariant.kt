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
    /** TASK-760: the description formats [supportedLanguageCodes]'s size. */
    val countPlaceholder: Boolean get() = false
    /** TASK-761 review: an INHERITED title may format the count as well. */
    val titleCountPlaceholder: Boolean get() = false
    val dirName: String
    val estimatedSizeMB: Long
    val supportedLanguageCodes: Set<String> get() = emptySet()
}
