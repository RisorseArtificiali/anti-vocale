package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry

/**
 * GH #49: a model's audio-length capability, derived from metadata only so the
 * model selection UI can declare it BEFORE the download (no per-model UI
 * strings). Terminology mirrors FAQ.md: the per-segment limit is the model's
 * own; anything beyond it is software chunking.
 */
sealed interface AudioLimit {
    /** The model accepts at most [seconds] of audio in one pass (e.g. Gemma: 30s). */
    data class HardCap(val seconds: Int) : AudioLimit

    /** No practical length limit: longer inputs are chunked in software and concatenated. */
    data object ChunkedAnyLength : AudioLimit

    /** No known limit (offline transducers without a cap, streaming models). */
    data object NoKnownLimit : AudioLimit
}

/**
 * @param maxAudioDuration hard per-pass cap (null = none)
 * @param chunkDurationSeconds chunk size; > 0 means the app chunks inputs at
 *   that size, i.e. any length is accepted
 */
fun audioLimit(maxAudioDuration: Int?, chunkDurationSeconds: Int): AudioLimit =
    when {
        // Chunking wins: Whisper/Qwen3 declare BOTH a 30s maxAudioDuration and
        // 30s chunks; with software chunking any length is accepted, so their
        // per-segment cap is an implementation detail, not a user-facing limit.
        chunkDurationSeconds > 0 -> AudioLimit.ChunkedAnyLength
        maxAudioDuration != null -> AudioLimit.HardCap(maxAudioDuration)
        else -> AudioLimit.NoKnownLimit
    }

/**
 * Single derivation points so every UI surface resolves the limit the same way
 * (GH #49). Catalog entries carry BOTH facts in their flags (the cap as
 * maxAudioDurationSeconds, the chunk size as chunkDurationSeconds); the
 * non-catalog Gemma variants keep their cap in ModelInfoProvider directly.
 */
fun audioLimitForCatalogEntry(entry: CatalogEntry): AudioLimit =
    audioLimit(
        maxAudioDuration = entry.flags.maxAudioDurationSeconds.takeIf { it > 0 },
        chunkDurationSeconds = entry.flags.chunkDurationSeconds,
    )

fun audioLimitForVariants(
    variants: List<ModelVariant>,
    chunkDurationSeconds: Int = 0,
): AudioLimit =
    audioLimit(
        maxAudioDuration = variants.firstNotNullOfOrNull { ModelInfoProvider.getInfo(it)?.maxAudioDuration },
        chunkDurationSeconds = chunkDurationSeconds,
    )
