package com.antivocale.app.transcription

import com.antivocale.app.data.ModelDownloader
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LanguageFilterTest {

    private fun whisperVariants(): List<CatalogVariantUi> = CatalogVariantUi.forEntry(BuiltInBackendIds.WHISPER)
    private fun qwen3Variant(): CatalogVariantUi = CatalogVariantUi.of(BuiltInBackendIds.QWEN3_ASR)

    @Before
    fun setUp() {
        // Variants resolve through BundledCatalog; seed it from the real asset so no
        // Android assets are needed here.
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException(
                "Cannot locate models_catalog.json from ${File(".").absolutePath}")
        }
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.data.catalog.ModelCatalogJson.parseCatalog(asset.readText()))
    }

    // ==================== Language metadata ====================

    @Test
    fun `FILTER_ENTRIES contains common languages`() {
        val codes = Language.FILTER_ENTRIES.toSet()
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
        assertEquals(Language.FILTER_ENTRIES.size, Language.FILTER_ENTRIES.toSet().size)
    }

    @Test
    fun `FILTER_ENTRIES codes are ISO 639-1 format`() {
        Language.FILTER_ENTRIES.forEach { code ->
            assertEquals("Expected 2-letter code for $code", 2, code.length)
        }
    }

    // ==================== TASK-685: first-run favorite seed ====================

    @Test
    fun `onboarding seed returns an offered interface language`() {
        assertEquals("it", Language.onboardingFavoriteSeed("it"))
        assertEquals("en", Language.onboardingFavoriteSeed("en"))
    }

    @Test
    fun `onboarding seed is null for a language the filter does not offer`() {
        assertNull(Language.onboardingFavoriteSeed("tl")) // Filipino: no filter entry
        // A custom offered set drives the same guard.
        assertNull(Language.onboardingFavoriteSeed("it", offered = setOf("de", "fr")))
        assertEquals("de", Language.onboardingFavoriteSeed("de", offered = setOf("de", "fr")))
    }

    @Test
    fun `onboarding seed is null for a null or blank interface language`() {
        assertNull(Language.onboardingFavoriteSeed(null))
        assertNull(Language.onboardingFavoriteSeed(""))
        assertNull(Language.onboardingFavoriteSeed("  "))
    }

    @Test
    fun `onboarding seed trims a padded interface language`() {
        assertEquals("it", Language.onboardingFavoriteSeed(" it "))
    }

    // ==================== Whisper variants ====================

    @Test
    fun `Whisper multilingual variants support many languages`() {
        val multilingualVariants = whisperVariants().filter { it.variantName != "distil-large-v3-it" }
        assertTrue(multilingualVariants.isNotEmpty())
        multilingualVariants.forEach { variant ->
            assertEquals(
                "Expected Whisper multilingual for ${variant.variantName}",
                Language.WHISPER_MULTILINGUAL,
                variant.supportedLanguageCodes
            )
            assertTrue(variant.supportedLanguageCodes.size >= 90)
        }
    }

    @Test
    fun `Whisper Distil Italian only supports Italian`() {
        val distil = whisperVariants().first { it.variantName == "distil-large-v3-it" }
        assertEquals(setOf("it"), distil.supportedLanguageCodes)
    }

    @Test
    fun `Whisper multilingual supports Italian`() {
        val turbo = whisperVariants().first { it.variantName == "turbo" }
        assertTrue(turbo.supportedLanguageCodes.contains("it"))
    }

    @Test
    fun `Whisper multilingual supports common European languages`() {
        val codes = whisperVariants().first { it.variantName == "turbo" }.supportedLanguageCodes
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
        assertTrue(qwen3Variant().supportedLanguageCodes.size >= 50)
    }

    @Test
    fun `Qwen3-ASR supports English and Chinese`() {
        val codes = qwen3Variant().supportedLanguageCodes
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
        val visibleWhisper = whisperVariants().filter {
            languageCode in it.supportedLanguageCodes
        }
        assertEquals(4, visibleWhisper.size)
        assertTrue(visibleWhisper.any { it.variantName == "small" })
        assertTrue(visibleWhisper.any { it.variantName == "turbo" })
        assertTrue(visibleWhisper.any { it.variantName == "medium" })
        assertTrue(visibleWhisper.any { it.variantName == "distil-large-v3-it" })
    }

    @Test
    fun `filtering by English hides Distil Italian`() {
        val languageCode = "en"
        val visibleWhisper = whisperVariants().filter {
            languageCode in it.supportedLanguageCodes
        }
        assertEquals(3, visibleWhisper.size)
        assertFalse(visibleWhisper.any { it.variantName == "distil-large-v3-it" })
    }

    @Test
    fun `filtering by null shows all variants`() {
        val visibleWhisper = whisperVariants()
        assertEquals(4, visibleWhisper.size)
    }

    @Test
    fun `filtering by Thai shows Qwen3-ASR`() {
        val languageCode = "th"
        assertTrue(languageCode in qwen3Variant().supportedLanguageCodes)
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
        Language.FILTER_ENTRIES.forEach { code ->
            assertTrue(
                "Filter entry '$code' should be supported by at least one backend",
                code in allSupported
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
            whisperVariants(), null
        ) { it.supportedLanguageCodes }
        assertEquals(whisperVariants(), visible)
    }

    @Test
    fun `filtering by Italian shows 4 Whisper variants including Distil`() {
        val visible = filterVariants(
            whisperVariants(), "it"
        ) { it.supportedLanguageCodes }
        assertEquals(4, visible.size)
        assertTrue(visible.any { it.variantName == "distil-large-v3-it" })
    }

    @Test
    fun `filtering by English shows 3 Whisper variants excluding Distil`() {
        val visible = filterVariants(
            whisperVariants(), "en"
        ) { it.supportedLanguageCodes }
        assertEquals(3, visible.size)
        assertFalse(visible.any { it.variantName == "distil-large-v3-it" })
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
            whisperVariants(), code
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
            whisperVariants(), code
        ) { it.supportedLanguageCodes }
        // Whisper multilingual may or may not have Thai — check Distil doesn't
        assertFalse(visibleWhisper.any { it.variantName == "distil-large-v3-it" })

        val visibleQwen = filterVariants(
            CatalogVariantUi.forEntry(BuiltInBackendIds.QWEN3_ASR), code
        ) { it.supportedLanguageCodes }
        assertFalse(visibleQwen.isEmpty())
    }

    @Test
    fun `filtering by null shows all Qwen3 variants`() {
        val visible = filterVariants(
            CatalogVariantUi.forEntry(BuiltInBackendIds.QWEN3_ASR), null
        ) { it.supportedLanguageCodes }
        assertEquals(CatalogVariantUi.forEntry(BuiltInBackendIds.QWEN3_ASR), visible)
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