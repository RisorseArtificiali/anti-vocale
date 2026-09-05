package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.BundledCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-364 drift guard: the audio-limit display fact (GH #49) moved from
 * ModelInfoProvider's Kotlin map into the catalog (`flags.maxAudioDurationSeconds`).
 * Pins the migration from two sides: the AudioLimit derived for the model list
 * is unchanged (display parity), and ModelInfoProvider does not reintroduce
 * caps for catalog-backed models (it remains the source only for the non-catalog
 * Gemma variants). Per-entry flag values are pinned by BundledModelCatalogTest.
 */
class ModelAudioLimitCatalogTest {

    /**
     * Expected label per catalog entry id. FORCING FUNCTION, deliberate: every
     * FUTURE catalog entry fails this map until it consciously declares its
     * expected audio-limit label here, so a capped model can never silently
     * fall back to NoKnownLimit (TASK-364's worst case).
     */
    private val expectedLabels = mapOf(
        "sherpa-onnx" to AudioLimit.ChunkedAnyLength, // 60s chunks (TASK-406)
        "whisper" to AudioLimit.ChunkedAnyLength, // 30s cap + 30s chunks
        "qwen3-asr" to AudioLimit.ChunkedAnyLength, // 30s cap + 30s chunks
        "nemotron-streaming" to AudioLimit.NoKnownLimit, // streaming, no cap
        "gigaam" to AudioLimit.ChunkedAnyLength, // 30s chunks (TASK-448: quality degrades from ~60s, collapses by ~90s per pass)
    )

    @Test
    fun `model-list audio limit is unchanged for every entry`() {
        for (entry in catalog()) {
            assertEquals(
                "entry ${entry.id} must keep its pre-TASK-364 label",
                expectedLabels.getValue(entry.id),
                audioLimitForCatalogEntry(entry),
            )
        }
    }

    @Test
    fun `model info no longer carries caps for catalog models`() {
        for (entry in catalog()) {
            for (variant in entry.variants) {
                val info = ModelInfoProvider.getInfoByDirName(variant.dirName)
                assertTrue("variant '${variant.dirName}' must still resolve to ModelInfo", info != null)
                assertNull(
                    "catalog variant '${variant.dirName}' must take its cap from the catalog flag, not ModelInfoProvider",
                    info?.maxAudioDuration,
                )
            }
        }
    }

    private fun catalog(): List<com.antivocale.app.data.catalog.CatalogEntry> {
        seedCatalogForTest()
        return BundledCatalog.entries()
    }
}
