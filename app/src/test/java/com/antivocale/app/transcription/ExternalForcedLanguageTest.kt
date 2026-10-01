package com.antivocale.app.transcription

import com.antivocale.app.data.ModelFamily

import com.antivocale.app.data.ExternalModelRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-462: the forced-language axis for external models. The families whose
 * engines condition on language consume the request pin over their own
 * defaults; the capability flag drives the offered set and the override gate
 * (a pin on a transducer would be a silent lie).
 */
class ExternalForcedLanguageTest {

    private fun record(family: ModelFamily, languages: List<String> = emptyList()) = ExternalModelRecord(
        id = "u", displayName = "m", dir = "/m", family = family, modelType = "",
        languages = languages, source = com.antivocale.app.data.ExternalModelSource.LOCAL,
        sourceUrl = null, files = emptyMap(), sizeBytes = 0, importedAt = 0,
    )

    @Test
    fun `the offered set is the record languages for capable families only`() {
        assertEquals(
            setOf("it", "en"),
            TranscriptionLanguagePolicy.offeredLanguagesForExternal(
                record(ModelFamily.WHISPER, listOf("it", "en"))))
        // A transducer auto-detects: the truthful empty set (disabled card).
        assertEquals(
            emptySet<String>(),
            TranscriptionLanguagePolicy.offeredLanguagesForExternal(
                record(ModelFamily.TRANSDUCER, listOf("it"))))
    }

    @Test
    fun `the override passes only concrete pins on capable families`() {
        assertEquals(
            "it",
            TranscriptionLanguagePolicy.externalOverride(
                record(ModelFamily.WHISPER, listOf("it", "en")), "it"))
        // Auto means detection: the family defaults apply.
        assertEquals(
            "",
            TranscriptionLanguagePolicy.externalOverride(
                record(ModelFamily.WHISPER, listOf("it")), TranscriptionLanguagePolicy.PREF_AUTO))
        // A pin on an incapable family is dropped, not silently applied.
        assertEquals(
            "",
            TranscriptionLanguagePolicy.externalOverride(
                record(ModelFamily.TRANSDUCER, listOf("it")), "it"))
        // Review: the UNTOUCHED DEFAULT sentinel is detection too (it passed
        // through as a literal forced language before the fix).
        assertEquals(
            "",
            TranscriptionLanguagePolicy.externalOverride(
                record(ModelFamily.WHISPER, listOf("it")), TranscriptionLanguagePolicy.PREF_SYSTEM))
        // The phone pin resolves through the device locale like the built-ins.
        assertEquals(
            "it",
            TranscriptionLanguagePolicy.externalOverride(
                record(ModelFamily.WHISPER, listOf("it")), TranscriptionLanguagePolicy.PREF_PHONE,
                phoneLanguage = "it"))
    }

    @Test
    fun `whisper consumes the override over the record default`() {
        val config = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
            .buildModelConfig(record(ModelFamily.WHISPER, listOf("de")), 4, "cpu", "it")
        assertEquals("it", config.whisper.language)
        // No pin: the record default (detection when the list is empty).
        val auto = ModelFamilySupport.forFamily(ModelFamily.WHISPER)
            .buildModelConfig(record(ModelFamily.WHISPER, listOf("de")), 4, "cpu", "")
        assertEquals("de", auto.whisper.language)
    }
}
