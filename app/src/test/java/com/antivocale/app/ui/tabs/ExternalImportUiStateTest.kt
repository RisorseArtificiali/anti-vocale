package com.antivocale.app.ui.tabs

import com.antivocale.app.data.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Defaults of the external-import selection state: the SenseVoice ITN switch must
 * default OFF, matching sherpa's default and the record semantics (an absent or
 * "false" option means no inverse text normalization).
 */
class ExternalImportUiStateTest {

    @Test
    fun `sensevoice itn defaults to false and the default options record it off`() {
        val state = ExternalImportUiState(family = ModelFamily.SENSE_VOICE)
        assertEquals(false, state.sensevoiceItn)
        assertEquals(mapOf("sensevoice.itn" to "false"), state.options())
    }

    @Test
    fun `decode language feeds the whisper option and derives the record tags (TASK-401)`() {
        val state = ExternalImportUiState(family = ModelFamily.WHISPER, decodeLanguage = "de")
        assertEquals(mapOf("whisper.language" to "de"), state.options())
        assertEquals(listOf("de"), state.languageCodes())
    }

    @Test
    fun `blank decode language means auto-detect with no option and no tags`() {
        val state = ExternalImportUiState(family = ModelFamily.WHISPER)
        assertEquals(emptyMap<String, String>(), state.options())
        assertEquals(emptyList<String>(), state.languageCodes())
        // families without a language option never emit one
        assertEquals(emptyMap<String, String>(), ExternalImportUiState(family = ModelFamily.TRANSDUCER, decodeLanguage = "de").options())
    }

    @Test
    fun `canary decode language conditions the recognizer option (TASK-408)`() {
        // Unlike whisper, a blank canary choice is not auto-detect (there is
        // none): the option stays absent and CanarySupport falls back to the
        // record languages, then "en", at config time.
        assertEquals(
            mapOf("canary.language" to "fr"),
            ExternalImportUiState(family = ModelFamily.CANARY, decodeLanguage = "fr").options())
        assertEquals(emptyMap<String, String>(), ExternalImportUiState(family = ModelFamily.CANARY).options())
    }

    @Test
    fun `switching family to canary blanks an incompatible carried-over language (review finding)`() {
        // "it" picked under whisper must not survive the switch: sherpa silently
        // substitutes "en" for unknown canary codes, so the stale value would
        // condition the recognizer as English while looking like a choice.
        val switched = ExternalImportUiState(family = ModelFamily.WHISPER, decodeLanguage = "it")
            .withFamily(ModelFamily.CANARY)
        assertEquals(ModelFamily.CANARY, switched.family)
        assertEquals("", switched.decodeLanguage)
        // a canary-valid language survives the switch
        assertEquals("de", ExternalImportUiState(family = ModelFamily.WHISPER, decodeLanguage = "de")
            .withFamily(ModelFamily.CANARY).decodeLanguage)
        // switching away from canary keeps any language (whisper accepts all)
        assertEquals("it", ExternalImportUiState(family = ModelFamily.CANARY, decodeLanguage = "it")
            .withFamily(ModelFamily.WHISPER).decodeLanguage)
    }
}
