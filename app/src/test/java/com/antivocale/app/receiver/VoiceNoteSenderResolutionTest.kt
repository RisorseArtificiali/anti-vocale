package com.antivocale.app.receiver

import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-271: the shared sender-correlation step (the extraction out of
 * ShareReceiverActivity's private matchSenderIdentity). The share path and
 * the detection coordinator both ride these rules, so the probe semantics
 * and the degradation contract are pinned here once.
 */
class VoiceNoteSenderResolutionTest {

    private fun preprocessor(returning: Double? = null, throwing: Boolean = false): AudioPreprocessor =
        mockk<AudioPreprocessor>().also { ap ->
            when {
                throwing -> every { ap.getAudioDuration(any()) } throws IOException("unreadable")
                returning != null -> every { ap.getAudioDuration(any()) } returns returning
                else -> every { ap.getAudioDuration(any()) } returns 0.0
            }
        }

    private fun cacheWith(vararg notes: VoiceNoteIdentityExtractor.VoiceNote): VoiceNoteIdentityCache =
        VoiceNoteIdentityCache().apply {
            accepting = true
            notes.forEach { remember(it) }
        }

    private fun note(durationSeconds: Long?) = VoiceNoteIdentityExtractor.VoiceNote(
        packageName = "com.whatsapp",
        sender = "Anna",
        durationSeconds = durationSeconds,
        postedAtMs = System.currentTimeMillis(),
    )

    @Test
    fun `the probe reads seconds and drops non-positive or failing reads`() {
        assertEquals(42L, VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = 42.5), "/ok"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = 0.0), "/zero"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = -1.0), "/neg"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(throwing = true), "/boom"))
    }

                @Test
    fun `matchForShare is free and label-less when nothing is cached`() {
        // The TASK-736 contract: every ordinary share pays nothing when the
        // identity listener is off; the probe must not even run.
        val ap = preprocessor(returning = 43.0)
        assertNull(
            VoiceNoteSenderResolution.matchForShare(VoiceNoteIdentityCache(), ap, "com.whatsapp", "/n.opus"))
        verify(exactly = 0) { ap.getAudioDuration(any()) }
    }

    @Test
    fun `matchForShare labels a duration-agreeing candidate`() {
        assertEquals(
            "Anna",
            VoiceNoteSenderResolution.matchForShare(
                cacheWith(note(durationSeconds = 42L)),
                preprocessor(returning = 42.4),
                "com.whatsapp",
                "/n.opus"))
    }

    @Test
    fun `matchForShare stays label-less when the probe fails`() {
        assertNull(
            VoiceNoteSenderResolution.matchForShare(
                cacheWith(note(durationSeconds = 42L)),
                preprocessor(throwing = true),
                "com.whatsapp",
                "/n.opus"))
    }
}
