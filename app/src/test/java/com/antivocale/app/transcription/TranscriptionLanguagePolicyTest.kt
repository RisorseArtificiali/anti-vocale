package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogEntry
import com.antivocale.app.data.catalog.CatalogFlags
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.catalog.CatalogDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance matrix for the transcription-language mapping
 * ([TranscriptionLanguagePolicy]). TASK-457 removed the app-locale pinning the
 * untouched "system" default used to carry (a silent pin is a translation trap
 * on wrong-language audio, GH #84): both sentinels now mean model-side
 * detection, pinned codes pass through, and the online languageOption mapping
 * is unchanged. TASK-458 adds the picker derivation: the offered set comes
 * from the active backend, so the UI list cannot drift from per-backend
 * support (GH #78).
 */
class TranscriptionLanguagePolicyTest {

    // ---- Offline mapping (Whisper): sentinels detect, pins pass through ----

    @Test
    fun `system default and blank legacy value map to model-side detection`() {
        assertEquals("", TranscriptionLanguagePolicy.resolveOffline(TranscriptionLanguagePolicy.PREF_SYSTEM))
        // A blank legacy value behaves like the untouched default.
        assertEquals("", TranscriptionLanguagePolicy.resolveOffline(""))
    }

    @Test
    fun `explicit auto maps to model-side detection`() {
        assertEquals("", TranscriptionLanguagePolicy.resolveOffline(TranscriptionLanguagePolicy.PREF_AUTO))
    }

    @Test
    fun `pinned language passes through`() {
        assertEquals("it", TranscriptionLanguagePolicy.resolveOffline("it"))
        assertEquals("de", TranscriptionLanguagePolicy.resolveOffline("de"))
    }

    // ---- The online languageOption (Nemotron) mapping is unchanged ----

    @Test
    fun `stream mapping sends auto for sentinels and passes codes through`() {
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(TranscriptionLanguagePolicy.PREF_SYSTEM))
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(""))
        assertEquals("auto", TranscriptionLanguagePolicy.resolveStream(TranscriptionLanguagePolicy.PREF_AUTO))
        assertEquals("it", TranscriptionLanguagePolicy.resolveStream("it"))
    }

    // ---- The per-entry dispatch shared by the load path and the benchmark ----

    @Test
    fun `resolveForEntry dispatches on the catalog flags`() {
        fun entry(languageOption: Boolean, passLanguage: Boolean) = CatalogEntry(
            id = "test",
            runtime = if (languageOption) "online" else "offline",
            modelType = "",
            family = "TRANSDUCER",
            display = CatalogDisplay.Literal("test"),
            flags = CatalogFlags(languageOption = languageOption, passLanguage = passLanguage),
            variants = listOf(variant(languages = listOf("it"))),
        )

        // languageOption (Nemotron): per-stream mapping.
        assertEquals(
            "auto",
            TranscriptionLanguagePolicy.resolveForEntry(
                entry(true, false), TranscriptionLanguagePolicy.PREF_SYSTEM),
        )
        // passLanguage (Whisper): the offline mapping; the "system" default
        // detects (TASK-457: no app-locale pinning anymore).
        assertEquals(
            "",
            TranscriptionLanguagePolicy.resolveForEntry(
                entry(false, true), TranscriptionLanguagePolicy.PREF_SYSTEM),
        )
        assertEquals(
            "it",
            TranscriptionLanguagePolicy.resolveForEntry(entry(false, true), "it"),
        )
        // Neither flag (Parakeet/Qwen3-ASR/GigaAM): always "".
        assertEquals(
            "",
            TranscriptionLanguagePolicy.resolveForEntry(entry(false, false), "it"),
        )
    }

    // ---- TASK-458: the picker derivation from the active backend ----

    private fun variant(
        dirName: String = "test-v",
        languages: List<String> = emptyList(),
    ) = CatalogVariant(
        name = dirName,
        dirName = dirName,
        estimatedSizeMB = 0,
        languages = languages,
        source = CatalogSource(kind = "url", template = "https://example.test/{file}"),
        files = emptyList(),
    )

    private fun whisperEntry(vararg variants: CatalogVariant, entryLanguages: List<String> = emptyList()) =
        CatalogEntry(
            id = "whisper",
            runtime = "offline",
            modelType = "whisper",
            family = "ENCODER_DECODER",
            display = CatalogDisplay.Literal("whisper"),
            flags = CatalogFlags(passLanguage = true),
            languages = entryLanguages,
            variants = variants.toList(),
        )

    @Test
    fun `whisper offers the installed variant's languages`() {
        val entry = whisperEntry(
            variant("sherpa-onnx-whisper-small", Language.WHISPER_MULTILINGUAL.toList()),
            variant("sherpa-onnx-whisper-distil-large-v3-it", Language.WHISPER_DISTIL_IT.toList()),
        )
        // The saved path's directory name picks the variant, like the load path.
        assertEquals(
            Language.WHISPER_MULTILINGUAL,
            TranscriptionLanguagePolicy.offeredLanguages("/models/sherpa-onnx-whisper-small", entry),
        )
        // A single-language variant (Distil-IT) offers exactly that language.
        assertEquals(
            setOf("it"),
            TranscriptionLanguagePolicy.offeredLanguages("/models/sherpa-onnx-whisper-distil-large-v3-it", entry),
        )
    }

    @Test
    fun `whisper falls back to the default variant and the entry-level languages`() {
        // Unknown directory name and no path: the default variant (first) decides.
        val entry = whisperEntry(
            variant("sherpa-onnx-whisper-small", listOf("en", "it")),
            variant("sherpa-onnx-whisper-turbo", listOf("en")),
        )
        assertEquals(
            setOf("en", "it"),
            TranscriptionLanguagePolicy.offeredLanguages("/models/unknown-dir", entry),
        )
        assertEquals(
            setOf("en", "it"),
            TranscriptionLanguagePolicy.offeredLanguages(null, entry),
        )
        // A variant without its own list falls back to the entry-level list.
        val entryLevel = whisperEntry(
            variant("sherpa-onnx-whisper-small"),
            entryLanguages = listOf("ru", "en"),
        )
        assertEquals(
            setOf("ru", "en"),
            TranscriptionLanguagePolicy.offeredLanguages("/models/sherpa-onnx-whisper-small", entryLevel),
        )
    }

    @Test
    fun `nemotron streaming offers its prompt-dictionary set`() {
        val entry = CatalogEntry(
            id = BuiltInBackendIds.NEMOTRON,
            runtime = "online",
            modelType = "",
            family = "TRANSDUCER",
            display = CatalogDisplay.Literal("nemotron"),
            flags = CatalogFlags(languageOption = true),
            languages = Language.NEMOTRON.toList(),
            variants = listOf(variant("nemotron-3.5-asr-streaming-0.6b-1120ms-int8")),
        )
        assertEquals(
            Language.NEMOTRON,
            TranscriptionLanguagePolicy.offeredLanguages(
                "/models/nemotron-3.5-asr-streaming-0.6b-1120ms-int8", entry),
        )
    }

    @Test
    fun `backends without language conditioning offer nothing`() {
        // Entries without either flag never read the pin (dispatch is flag-driven,
        // not id-driven: a hypothetical flagged entry would offer its languages).
        val unflagged = CatalogEntry(
            id = BuiltInBackendIds.PARAKEET,
            runtime = "offline",
            modelType = "nemo_transducer",
            family = "TRANSDUCER",
            display = CatalogDisplay.Literal("parakeet"),
            flags = CatalogFlags(),
            languages = listOf("it"),
            variants = listOf(variant("parakeet-tdt-0.6b-v3-smoothquant")),
        )
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages(
                "/models/parakeet-tdt-0.6b-v3-smoothquant", unflagged),
        )
        // And no entry at all (unknown/external/llm ids): empty set.
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages(null, null),
        )
        // The LLM backend, the disabled GGUF backend, external imports, and a
        // missing whisper catalog entry: no conditioning, empty set.
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages("/models/gemma.task", null),
        )
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages("/models/gemma.gguf", null),
        )
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages("/files/external-models/abc123", null),
        )
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguages(null, null),
        )
    }

    // ---- TASK-458: the stored pin vs the offered set ----

    @Test
    fun `pin state classifies sentinels, supported and unsupported pins`() {
        val offered = setOf("it", "de")
        assertEquals(
            TranscriptionLanguagePolicy.PinState.NOT_PINNED,
            TranscriptionLanguagePolicy.pinState(TranscriptionLanguagePolicy.PREF_SYSTEM, offered),
        )
        assertEquals(
            TranscriptionLanguagePolicy.PinState.NOT_PINNED,
            TranscriptionLanguagePolicy.pinState(TranscriptionLanguagePolicy.PREF_AUTO, offered),
        )
        assertEquals(
            TranscriptionLanguagePolicy.PinState.NOT_PINNED,
            TranscriptionLanguagePolicy.pinState("", offered),
        )
        assertEquals(
            TranscriptionLanguagePolicy.PinState.SUPPORTED_PIN,
            TranscriptionLanguagePolicy.pinState("it", offered),
        )
        assertEquals(
            TranscriptionLanguagePolicy.PinState.UNSUPPORTED_PIN,
            TranscriptionLanguagePolicy.pinState("ru", offered),
        )
    }
}
