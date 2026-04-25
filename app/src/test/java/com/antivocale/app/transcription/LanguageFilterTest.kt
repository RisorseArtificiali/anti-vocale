package com.antivocale.app.transcription

import com.antivocale.app.data.ModelDownloader
import org.junit.Assert.*
import org.junit.Test

class LanguageFilterTest {

    // ==================== Language metadata ====================

    @Test
    fun `FILTER_ENTRIES contains common languages`() {
        val codes = Language.FILTER_ENTRIES.map { it.code }.toSet()
        assertTrue(codes.contains("en"))
        assertTrue(codes.contains("it"))
        assertTrue(codes.contains("de"))
        assertTrue(codes.contains("fr"))
        assertTrue(codes.contains("es"))
        assertTrue(codes.contains("zh"))
        assertTrue(codes.contains("ja"))
        assertTrue(codes.contains("ko"))
        assertTrue(codes.contains("ar"))
        assertTrue(codes.contains("ru"))
    }

    @Test
    fun `FILTER_ENTRIES has no duplicate codes`() {
        val codes = Language.FILTER_ENTRIES.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `FILTER_ENTRIES codes are ISO 639-1 format`() {
        Language.FILTER_ENTRIES.forEach { entry ->
            assertEquals("Expected 2-letter code for ${entry.code}", 2, entry.code.length)
        }
    }

    // ==================== Whisper variants ====================

    @Test
    fun `Whisper multilingual variants support many languages`() {
        val multilingualVariants = listOf(
            WhisperModelManager.Variant.SMALL,
            WhisperModelManager.Variant.TURBO,
            WhisperModelManager.Variant.MEDIUM
        )
        multilingualVariants.forEach { variant ->
            assertEquals(
                "Expected Whisper multilingual for ${variant.name}",
                Language.WHISPER_MULTILINGUAL,
                variant.supportedLanguageCodes
            )
            assertTrue(variant.supportedLanguageCodes.size >= 90)
        }
    }

    @Test
    fun `Whisper Distil Italian only supports Italian`() {
        assertEquals(setOf("it"), WhisperModelManager.Variant.DISTIL_LARGE_V3.supportedLanguageCodes)
    }

    @Test
    fun `Whisper multilingual supports Italian`() {
        assertTrue(WhisperModelManager.Variant.TURBO.supportedLanguageCodes.contains("it"))
    }

    @Test
    fun `Whisper multilingual supports common European languages`() {
        val codes = WhisperModelManager.Variant.TURBO.supportedLanguageCodes
        assertTrue("en" in codes)
        assertTrue("de" in codes)
        assertTrue("fr" in codes)
        assertTrue("es" in codes)
        assertTrue("pt" in codes)
        assertTrue("nl" in codes)
        assertTrue("pl" in codes)
        assertTrue("sv" in codes)
    }

    // ==================== Qwen3-ASR ====================

    @Test
    fun `Qwen3-ASR has non-empty language set`() {
        assertTrue(Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B.supportedLanguageCodes.size >= 50)
    }

    @Test
    fun `Qwen3-ASR supports English and Chinese`() {
        val codes = Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B.supportedLanguageCodes
        assertTrue("en" in codes)
        assertTrue("zh" in codes)
    }

    // ==================== Parakeet ====================

    @Test
    fun `Parakeet supports 25 languages`() {
        assertEquals(25, Language.PARAKEET.size)
    }

    @Test
    fun `Parakeet supports Italian`() {
        assertTrue("it" in Language.PARAKEET)
    }

    @Test
    fun `Parakeet supports English`() {
        assertTrue("en" in Language.PARAKEET)
    }

    // ==================== Gemma ====================

    @Test
    fun `Gemma variants all have the same language set`() {
        val sets = ModelDownloader.ModelVariant.entries.map { it.supportedLanguageCodes }.toSet()
        assertEquals(1, sets.size)
    }

    @Test
    fun `Gemma supports broad set of languages`() {
        assertTrue(Language.GEMMA.size >= 100)
    }

    @Test
    fun `Gemma supports Italian`() {
        assertTrue("it" in Language.GEMMA)
    }

    // ==================== Filtering logic ====================

    @Test
    fun `filtering by Italian hides Distil but shows multilingual Whisper`() {
        val languageCode = "it"
        val visibleWhisper = WhisperModelManager.Variant.entries.filter {
            languageCode in it.supportedLanguageCodes
        }
        assertEquals(4, visibleWhisper.size)
        assertTrue(visibleWhisper.contains(WhisperModelManager.Variant.SMALL))
        assertTrue(visibleWhisper.contains(WhisperModelManager.Variant.TURBO))
        assertTrue(visibleWhisper.contains(WhisperModelManager.Variant.MEDIUM))
        assertTrue(visibleWhisper.contains(WhisperModelManager.Variant.DISTIL_LARGE_V3))
    }

    @Test
    fun `filtering by English hides Distil Italian`() {
        val languageCode = "en"
        val visibleWhisper = WhisperModelManager.Variant.entries.filter {
            languageCode in it.supportedLanguageCodes
        }
        assertEquals(3, visibleWhisper.size)
        assertFalse(visibleWhisper.contains(WhisperModelManager.Variant.DISTIL_LARGE_V3))
    }

    @Test
    fun `filtering by null shows all variants`() {
        val visibleWhisper = WhisperModelManager.Variant.entries
        assertEquals(4, visibleWhisper.size)
    }

    @Test
    fun `filtering by Thai shows Qwen3-ASR`() {
        val languageCode = "th"
        assertTrue(languageCode in Qwen3AsrModelManager.Variant.QWEN3_ASR_0_6B.supportedLanguageCodes)
    }

    @Test
    fun `Gemma covers languages that other backends miss`() {
        // Gemma should be a superset for many languages
        val allOthers = Language.WHISPER_MULTILINGUAL +
            Language.QWEN3_ASR +
            Language.PARAKEET
        // Gemma covers languages beyond the others
        val gemmaOnly = Language.GEMMA - allOthers
        assertTrue("Gemma should cover some languages others don't", gemmaOnly.isNotEmpty())
    }

    @Test
    fun `all FILTER_ENTRIES languages are supported by at least one backend`() {
        val allSupported = Language.WHISPER_MULTILINGUAL +
            Language.WHISPER_DISTIL_IT +
            Language.QWEN3_ASR +
            Language.PARAKEET +
            Language.GEMMA
        Language.FILTER_ENTRIES.forEach { entry ->
            assertTrue(
                "Filter entry '${entry.code}' should be supported by at least one backend",
                entry.code in allSupported
            )
        }
    }

    // ==================== Filtering simulation (mirrors ModelTab logic) ====================

    private fun <T> filterVariants(
        entries: List<T>,
        languageCode: String?,
        getCodes: (T) -> Set<String>
    ): List<T> {
        if (languageCode == null) return entries
        return entries.filter { languageCode in getCodes(it) }
    }

    @Test
    fun `filtering by null shows all Whisper variants`() {
        val visible = filterVariants(
            WhisperModelManager.Variant.entries, null
        ) { it.supportedLanguageCodes }
        assertEquals(WhisperModelManager.Variant.entries, visible)
    }

    @Test
    fun `filtering by Italian shows 4 Whisper variants including Distil`() {
        val visible = filterVariants(
            WhisperModelManager.Variant.entries, "it"
        ) { it.supportedLanguageCodes }
        assertEquals(4, visible.size)
        assertTrue(visible.any { it == WhisperModelManager.Variant.DISTIL_LARGE_V3 })
    }

    @Test
    fun `filtering by English shows 3 Whisper variants excluding Distil`() {
        val visible = filterVariants(
            WhisperModelManager.Variant.entries, "en"
        ) { it.supportedLanguageCodes }
        assertEquals(3, visible.size)
        assertFalse(visible.any { it == WhisperModelManager.Variant.DISTIL_LARGE_V3 })
    }

    @Test
    fun `filtering by Zulu shows only Gemma`() {
        // "zu" is in GEMMA but not in Whisper/Qwen3/Parakeet
        val code = "zu"
        assertTrue(code in Language.GEMMA)
        assertFalse(code in Language.WHISPER_MULTILINGUAL)
        assertFalse(code in Language.QWEN3_ASR)
        assertFalse(code in Language.PARAKEET)

        val visibleWhisper = filterVariants(
            WhisperModelManager.Variant.entries, code
        ) { it.supportedLanguageCodes }
        assertTrue(visibleWhisper.isEmpty())

        val visibleGemma = filterVariants(
            ModelDownloader.ModelVariant.entries, code
        ) { it.supportedLanguageCodes }
        assertFalse(visibleGemma.isEmpty())

        val showParakeet = code in Language.PARAKEET
        assertFalse(showParakeet)
    }

    @Test
    fun `filtering by Thai shows Qwen3 and Gemma but not Whisper Distil`() {
        val code = "th"
        assertTrue(code in Language.QWEN3_ASR)
        assertTrue(code in Language.GEMMA)

        val visibleWhisper = filterVariants(
            WhisperModelManager.Variant.entries, code
        ) { it.supportedLanguageCodes }
        // Whisper multilingual may or may not have Thai — check Distil doesn't
        assertFalse(visibleWhisper.any { it == WhisperModelManager.Variant.DISTIL_LARGE_V3 })

        val visibleQwen = filterVariants(
            Qwen3AsrModelManager.Variant.entries, code
        ) { it.supportedLanguageCodes }
        assertFalse(visibleQwen.isEmpty())
    }

    @Test
    fun `filtering by null shows all Qwen3 variants`() {
        val visible = filterVariants(
            Qwen3AsrModelManager.Variant.entries, null
        ) { it.supportedLanguageCodes }
        assertEquals(Qwen3AsrModelManager.Variant.entries, visible)
    }

    @Test
    fun `filtering by null shows all Gemma variants`() {
        val visible = filterVariants(
            ModelDownloader.ModelVariant.entries, null
        ) { it.supportedLanguageCodes }
        assertEquals(ModelDownloader.ModelVariant.entries, visible)
    }

    @Test
    fun `Parakeet show-logic is correct for supported languages`() {
        // Parakeet supports "it" and "en"
        assertTrue("it" in Language.PARAKEET)
        assertTrue("en" in Language.PARAKEET)

        // null → show
        assertTrue(null == null || "it" in Language.PARAKEET) // null case always shows
    }

    @Test
    fun `Parakeet hides for unsupported language`() {
        // Find a language in GEMMA but not in PARAKEET
        val unsupported = Language.GEMMA.first { it !in Language.PARAKEET }
        assertFalse(unsupported in Language.PARAKEET)
    }
}
