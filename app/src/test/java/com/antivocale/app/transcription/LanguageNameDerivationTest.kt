package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Road test 2026-10-08 (maintainer direction): the languages a repo-URL
 * import supports are derived from the model/repo NAME. These tests pin the
 * precision contract: whole tokens only, no substring matches, and the
 * maintainer's own example (paraformer-zh carries zh in its name and must
 * show it on the card).
 */
class LanguageNameDerivationTest {

    @Test
    fun `paraformer zh carries zh`() {
        assertEquals(listOf("zh"),
            Language.deriveLanguageHintsFromName("sherpa-onnx-paraformer-zh"))
    }

    @Test
    fun `repo names with underscore separators derive too`() {
        assertEquals(listOf("uk"),
            Language.deriveLanguageHintsFromName("sherpa-onnx-moonshine-base-uk-quantized-2026-02-27"))
    }

    @Test
    fun `english full names map to their codes`() {
        assertEquals(listOf("en"),
            Language.deriveLanguageHintsFromName("whisper-small-english"))
        assertEquals(listOf("ru"),
            Language.deriveLanguageHintsFromName("gorzakres-russian-vosk"))
    }

    @Test
    fun `multiple codes in one name are all kept in name order`() {
        val derived = Language.deriveLanguageHintsFromName("custom-asr-zh-en-bilingual")
        assertEquals(listOf("zh", "en"), derived)
    }

    @Test
    fun `substrings never match`() {
        // "decoder" contains de, "spanish" contains spa-ish, "enter" contains
        // en: none of these are whole tokens.
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("my-decoder-center-model"))
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("whisper-enterprise-edition"))
    }

    @Test
    fun `unknown names yield an empty list, not a guess`() {
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("mymodel-v2-final"))
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName(""))
    }

    @Test
    fun `version tags and numerals never match`() {
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("something-2026-02-27-build"))
    }

    @Test
    fun `wordlike codes never match as bare tokens`() {
        // "hi" and "no" are English words as much as codes: a bare-token
        // match would pin Hindi on a hi-res build and Norwegian on a
        // no-vad build (code review F3).
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("whisper-v3-hi-res"))
        assertEquals(emptyList<String>(),
            Language.deriveLanguageHintsFromName("asr-no-vad-model"))
    }

    @Test
    fun `wordlike languages still derive through english names`() {
        assertEquals(listOf("hi"),
            Language.deriveLanguageHintsFromName("indic-asr-hindi-finetune"))
        assertEquals(listOf("no"),
            Language.deriveLanguageHintsFromName("asr-norwegian-vosk"))
    }
}
