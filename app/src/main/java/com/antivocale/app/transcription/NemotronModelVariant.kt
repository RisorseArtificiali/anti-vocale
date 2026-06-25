package com.antivocale.app.transcription

import com.antivocale.app.R

/**
 * Single-variant descriptor for the Nemotron streaming ASR model.
 *
 * Nemotron has no user-facing variant selector (the downloader is `Unit`-keyed),
 * but the shared [com.antivocale.app.ui.components.ModelVariantCard] requires a
 * [ModelVariant] to render. This object supplies that.
 */
object NemotronModelVariant : ModelVariant {
    override val titleResId: Int = R.string.nemotron_title
    override val descriptionResId: Int = R.string.nemotron_description
    override val dirName: String = NemotronModelManager.NEMOTRON_MODEL_DIR
    override val estimatedSizeMB: Long = 640L  // int8 (~640MB) — keep in sync with NemotronDownloader.ESTIMATED_SIZE_MB
    override val supportedLanguageCodes: Set<String> = Language.NEMOTRON
}
