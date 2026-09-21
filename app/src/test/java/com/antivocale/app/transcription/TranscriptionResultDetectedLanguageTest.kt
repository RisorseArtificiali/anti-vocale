package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-615: SenseVoice reports its language as the raw decode token
 * "<|en|>"; the chip, the pin flow and the persisted row must see the bare
 * code, and unusable shapes must degrade to "no detection" (null).
 */
class TranscriptionResultDetectedLanguageTest {

    @Test
    fun `the sensevoice token wrapper is stripped`() {
        assertEquals("en", TranscriptionResult.normalizedDetectedLanguage("<|en|>"))
        assertEquals("it", TranscriptionResult.normalizedDetectedLanguage("<|it|>"))
    }

    @Test
    fun `a plain whisper code passes through unchanged`() {
        assertEquals("en", TranscriptionResult.normalizedDetectedLanguage("en"))
        assertEquals("de", TranscriptionResult.normalizedDetectedLanguage("de"))
    }

    @Test
    fun `blank and unusable shapes mean no detection`() {
        assertNull(TranscriptionResult.normalizedDetectedLanguage(null))
        assertNull(TranscriptionResult.normalizedDetectedLanguage(""))
        assertNull(TranscriptionResult.normalizedDetectedLanguage("   "))
        assertNull(TranscriptionResult.normalizedDetectedLanguage("<||>"))
        assertNull(TranscriptionResult.normalizedDetectedLanguage("<|en|>junk"))
    }
}
