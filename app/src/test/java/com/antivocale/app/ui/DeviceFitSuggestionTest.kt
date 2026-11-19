package com.antivocale.app.ui

import com.antivocale.app.data.ModelAccuracy
import com.antivocale.app.util.DeviceCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1021: the pure core of the device-fit suggestions. The pins mirror
 * the design table: the tier budgets (Parakeet compact in, the full 862 out
 * on entry; Medium and the Gemma lane out on mid), and the maintainer's
 * ordering rule - inside the budget, language coverage and measured quality
 * decide, size is only the tiebreaker (a size-sorted list would lead with
 * the Hebrew zipformer for an Italian user).
 */
class DeviceFitSuggestionTest {

    private data class M(
        val id: String,
        val variant: String,
        val sizeMb: Long,
        val langs: Set<String>,
    )

    private fun wer(modelId: String, variant: String, language: String, value: Double) =
        ModelAccuracy.Measurement(
            modelId = modelId, variant = variant, language = language,
            metric = "WER", value = value, corpus = "test", chain = "app-chain", date = "2026-11",
        )

    @Test
    fun `entry budget keeps the comfortable set and cuts the heavy class`() {
        assertTrue(DeviceFitSuggestion.fits(640, DeviceCompatibility.RamTier.CONSTRAINED))
        assertTrue(DeviceFitSuggestion.fits(750, DeviceCompatibility.RamTier.CONSTRAINED))
        assertFalse(DeviceFitSuggestion.fits(862, DeviceCompatibility.RamTier.CONSTRAINED))
        assertFalse(DeviceFitSuggestion.fits(984, DeviceCompatibility.RamTier.CONSTRAINED))
    }

    @Test
    fun `mid budget admits the 1GB multilinguals and cuts Medium and the LLM lane`() {
        assertTrue(DeviceFitSuggestion.fits(984, DeviceCompatibility.RamTier.STANDARD))
        assertTrue(DeviceFitSuggestion.fits(1500, DeviceCompatibility.RamTier.STANDARD))
        assertFalse(DeviceFitSuggestion.fits(1533, DeviceCompatibility.RamTier.STANDARD))
        assertFalse(DeviceFitSuggestion.fits(3800, DeviceCompatibility.RamTier.STANDARD))
    }

    @Test
    fun `high band has no budget - everything fits, the filter does not exist`() {
        assertTrue(DeviceFitSuggestion.fits(3800, DeviceCompatibility.RamTier.HIGH))
        assertTrue(DeviceFitSuggestion.fits(124, DeviceCompatibility.RamTier.HIGH))
    }

    @Test
    fun `ordering puts the user language first, then quality, then size`() {
        val hebrew = M("external", "zipformer-he", 66, setOf("he"))
        val gigaam = M("sherpa-onnx", "gigaam-v3", 124, setOf("ru"))
        val parakeet = M("sherpa-onnx", "parakeet-tdt-0.6b-v3-int8", 640, setOf("it", "en"))
        val small = M("whisper", "whisper-small", 466, setOf("it", "en"))
        val measurements = listOf(
            wer("sherpa-onnx", "parakeet-tdt-0.6b-v3-int8", "it", 4.5),
            wer("whisper", "whisper-small", "it", 6.9),
        )

        val ordered = DeviceFitSuggestion.order(
            listOf(hebrew, gigaam, parakeet, small),
            userLanguage = "it",
            sizeMb = { it.sizeMb },
            languages = { it.langs },
            wer = { DeviceFitSuggestion.qualityWer(it.id, it.variant, "it", measurements) },
        )

        // Italian-capable first, best WER first; the non-Italian set follows
        // by size (the Hebrew model leads it - for a Hebrew user it would).
        assertEquals(listOf("parakeet-tdt-0.6b-v3-int8", "whisper-small", "zipformer-he", "gigaam-v3"),
            ordered.map { it.variant })
    }

    @Test
    fun `unmeasured models sort after measured ones within the language group`() {
        val measured = M("whisper", "whisper-small", 466, setOf("it"))
        val unmeasured = M("qwen", "qwen3-asr", 500, setOf("it"))
        val measurements = listOf(wer("whisper", "whisper-small", "it", 7.0))

        val ordered = DeviceFitSuggestion.order(
            listOf(unmeasured, measured),
            userLanguage = "it",
            sizeMb = { it.sizeMb },
            languages = { it.langs },
            wer = { DeviceFitSuggestion.qualityWer(it.id, it.variant, "it", measurements) },
        )

        assertEquals(listOf("whisper-small", "qwen3-asr"), ordered.map { it.variant })
    }

    @Test
    fun `orderEntries keys quality on the best MEASURED eligible variant, not the smallest`() {
        // The entry-level path the tab actually uses. The Whisper entry's
        // SMALLEST variant is unmeasured; its bigger distil variant carries
        // the best measured WER. The review found minBy-size burying the
        // measured flagship; this pin holds the fix.
        fun variant(dirName: String, sizeMb: Long, langs: Set<String>) =
            com.antivocale.app.transcription.CatalogVariantUi(
                backendId = "whisper", variantName = dirName, dirName = dirName,
                titleResId = 0, descriptionResId = 0, estimatedSizeMB = sizeMb,
                supportedLanguageCodes = langs,
            )
        val entry = entry("whisper")
        val other = entry.copy(id = "sherpa-onnx")
        val whisperEligible = listOf(
            variant("whisper-tiny", 466, setOf("it")),                 // smallest, unmeasured
            variant("whisper-distil-large-v3-it", 984, setOf("it")),   // measured 3.46
        )
        val parakeetEligible = listOf(
            com.antivocale.app.transcription.CatalogVariantUi(
                backendId = "sherpa-onnx", variantName = "parakeet-int8", dirName = "parakeet-int8",
                titleResId = 0, descriptionResId = 0, estimatedSizeMB = 640,
                supportedLanguageCodes = setOf("it"),
            ),
        )
        val measurements = listOf(
            wer("whisper", "whisper-distil-large-v3-it", "it", 3.46),
            wer("sherpa-onnx", "parakeet-int8", "it", 4.3),
        )

        val ordered = DeviceFitSuggestion.orderEntries(
            listOf(entry, other),
            userLanguage = "it",
            measurements = measurements,
            eligible = { if (it.id == "whisper") whisperEligible else parakeetEligible },
        )

        // Whisper first: its best MEASURED eligible variant (3.46) beats
        // parakeet (4.3), even though its smallest variant is unmeasured.
        assertEquals(listOf("whisper", "sherpa-onnx"), ordered.map { it.id })
    }

    @Test
    fun `orderEntries drops entries with no eligible variants`() {
        fun variant(id: String, dirName: String, sizeMb: Long) =
            com.antivocale.app.transcription.CatalogVariantUi(
                backendId = id, variantName = dirName, dirName = dirName,
                titleResId = 0, descriptionResId = 0, estimatedSizeMB = sizeMb,
                supportedLanguageCodes = setOf("ru"),
            )
        val whisper = entry("whisper")
        val ordered = DeviceFitSuggestion.orderEntries(
            listOf(whisper),
            userLanguage = "it",
            measurements = emptyList(),
            eligible = { _ -> emptyList() },
        )
        assertTrue(ordered.isEmpty())
    }

    private fun entry(id: String) = com.antivocale.app.data.catalog.CatalogEntry(
        id = id, runtime = "offline", modelType = "whisper",
        family = "ENCODER_DECODER",
        display = com.antivocale.app.data.catalog.CatalogDisplay.Literal(id),
        variants = emptyList(),
    )

    @Test
    fun `orderingLanguage maps the legacy locale codes to the modern ones`() {
        assertEquals("he", DeviceFitSuggestion.orderingLanguage("iw"))
        assertEquals("id", DeviceFitSuggestion.orderingLanguage("in"))
        assertEquals("yi", DeviceFitSuggestion.orderingLanguage("ji"))
        assertEquals("it", DeviceFitSuggestion.orderingLanguage("it"))
    }

    @Test
    fun `the same list for a Hebrew user leads with the Hebrew model`() {
        val hebrew = M("external", "zipformer-he", 66, setOf("he"))
        val parakeet = M("sherpa-onnx", "parakeet-tdt-0.6b-v3-int8", 640, setOf("it", "en"))

        val ordered = DeviceFitSuggestion.order(
            listOf(parakeet, hebrew),
            userLanguage = "he",
            sizeMb = { it.sizeMb },
            languages = { it.langs },
            wer = { DeviceFitSuggestion.qualityWer(it.id, it.variant, "he", emptyList()) },
        )

        assertEquals("zipformer-he", ordered.first().variant)
    }
}
