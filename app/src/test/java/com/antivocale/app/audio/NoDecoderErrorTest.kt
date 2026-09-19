package com.antivocale.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * GH #18: with the copy-side extension whitelist gone, the typed
 * "unsupported format" refusal is PreprocessingError.NoDecoder, raised at
 * the decoder-creation call both decode paths share. These tests pin the
 * error's shape and the format-token sanitizer that feeds it (the token is
 * interpolated into a localized string, so garbage must never pass).
 */
@RunWith(JUnit4::class)
class NoDecoderErrorTest {

    @Test
    fun `NoDecoder message names the format and the mime`() {
        val error = AudioPreprocessor.PreprocessingError.NoDecoder("ts", "audio/vnd.dts")
        assertEquals("No decoder on this device for .ts audio (audio/vnd.dts)", error.message)
    }

    @Test
    fun `formatToken takes the lowercased extension of the file name`() {
        assertEquals("ts", AudioPreprocessor.formatToken("/data/shared_audio/shared_1_abc.TS"))
        assertEquals("mka", AudioPreprocessor.formatToken("recording.mka"))
    }

    @Test
    fun `formatToken ignores an extension that only exists in a directory name`() {
        // The last dot belongs to a path segment, not the file name.
        assertEquals("", AudioPreprocessor.formatToken("/storage/emulated/0/v2.4/file"))
    }

    @Test
    fun `formatToken rejects non-token extensions before they reach a string resource`() {
        // Same guard class the retired whitelist message used: 1..8
        // alphanumerics only; anything else renders as "" (mime-only message).
        assertEquals("", AudioPreprocessor.formatToken("file.abcdefghijklmnop"))
        assertEquals("", AudioPreprocessor.formatToken("file.a-b"))
        assertEquals("", AudioPreprocessor.formatToken("file."))
        assertEquals("", AudioPreprocessor.formatToken("file"))
    }
}
