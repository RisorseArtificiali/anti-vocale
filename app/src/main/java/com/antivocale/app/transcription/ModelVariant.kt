package com.antivocale.app.transcription

/**
 * Common interface for transcription model variants.
 *
 * Both [WhisperModelManager.Variant] and [Qwen3AsrModelManager.Variant] implement this,
 * enabling generic UI components like [ModelVariantCard] to render any variant.
 */
interface ModelVariant {
    val titleResId: Int
    val descriptionResId: Int
    val dirName: String
    val estimatedSizeMB: Long
}
