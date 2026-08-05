package com.antivocale.app.transcription

import com.antivocale.app.R

/**
 * Single-variant descriptor for the GigaAM v3 model.
 *
 * GigaAM has no user-facing variant selector (the downloader is `Unit`-keyed),
 * but the shared [com.antivocale.app.ui.components.ModelVariantCard] requires a
 * [ModelVariant] to render. This object supplies that.
 */
object GigaAmModelVariant : ModelVariant {
    override val titleResId: Int = R.string.gigaam_title
    override val descriptionResId: Int = R.string.gigaam_description
    override val dirName: String = GigaAmModelManager.GIGAAM_MODEL_DIR
    override val estimatedSizeMB: Long = GigaAmModelManager.ESTIMATED_SIZE_MB
    override val supportedLanguageCodes: Set<String> = Language.GIGAAM
}
