package com.antivocale.app.data.catalog

import com.antivocale.app.R

/**
 * Resolves catalog resource-key NAMES to [R.string] ids (spec: catalog-driven
 * platform v3). The bundled catalog stores the localized strings BY NAME so the
 * data stays locale-neutral and the code stays single-sourced here. The map is
 * deliberately complete and hand-maintained: adding a resource key to the
 * catalog without mapping it here fails the [com.antivocale.app.data.catalog]
 * test suite (fail-fast), and a name that no longer exists in R.string fails
 * compilation.
 */
object CatalogStringKeys {

    private val KEYS: Map<String, Int> = mapOf(
        "parakeet_name" to R.string.parakeet_name,
        "parakeet_description" to R.string.parakeet_description,
        "parakeet_smoothquant_title" to R.string.parakeet_smoothquant_title,
        "parakeet_smoothquant_description" to R.string.parakeet_smoothquant_description,
        "parakeet_stock_title" to R.string.parakeet_stock_title,
        "parakeet_stock_description" to R.string.parakeet_stock_description,

        "whisper_title" to R.string.whisper_title,
        "whisper_description" to R.string.whisper_description,
        "whisper_small_title" to R.string.whisper_small_title,
        "whisper_small_description" to R.string.whisper_small_description,
        "whisper_turbo_title" to R.string.whisper_turbo_title,
        "whisper_turbo_description" to R.string.whisper_turbo_description,
        "whisper_medium_title" to R.string.whisper_medium_title,
        "whisper_medium_description" to R.string.whisper_medium_description,
        "whisper_distil_large_v3_title" to R.string.whisper_distil_large_v3_title,
        "whisper_distil_large_v3_description" to R.string.whisper_distil_large_v3_description,
        "best_italian_badge" to R.string.best_italian_badge,

        "qwen3_asr_title" to R.string.qwen3_asr_title,
        "qwen3_asr_description" to R.string.qwen3_asr_description,
        "qwen3_asr_0_6b_title" to R.string.qwen3_asr_0_6b_title,
        "qwen3_asr_0_6b_description" to R.string.qwen3_asr_0_6b_description,

        "nemotron_name" to R.string.nemotron_name,
        "nemotron_description" to R.string.nemotron_description,

        "gigaam_name" to R.string.gigaam_name,
        "whistle_name" to R.string.whistle_name,
        "llm_backend_name" to R.string.llm_backend_name,
        "gemma_advanced_features_description" to R.string.gemma_advanced_features_description,
        "model_title_gemma_4_e2b" to R.string.model_title_gemma_4_e2b,
        "model_title_gemma_4_e4b" to R.string.model_title_gemma_4_e4b,
        "model_title_gemma_3n_e2b" to R.string.model_title_gemma_3n_e2b,
        "model_title_gemma_3n_e4b" to R.string.model_title_gemma_3n_e4b,
        "whistle_description" to R.string.whistle_description,
        "gigaam_description" to R.string.gigaam_description,

        "model_info_best_for_parakeet" to R.string.model_info_best_for_parakeet,
        "model_info_best_for_gigaam" to R.string.model_info_best_for_gigaam,
    )

    /** The resource ids a catalog references (for iteration in fail-fast tests). */
    val resourceKeys: Set<String> get() = KEYS.keys

    /** Resolves a catalog resource-key name to its [R.string] id, or throws (fail-fast). */
    fun resolve(key: String): Int =
        KEYS[key] ?: throw IllegalArgumentException("catalog string key not mapped to R.string: $key")
}