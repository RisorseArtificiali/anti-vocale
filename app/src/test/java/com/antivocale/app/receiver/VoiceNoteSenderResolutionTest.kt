package com.antivocale.app.receiver

import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-271: the shared sender-correlation step (the extraction out of
 * ShareReceiverActivity's private matchSenderIdentity). The share path and
 * the detection route both ride these rules, so the probe semantics and
 * the degradation contract are pinned here once. TASK-821 adds the
 * automatic route's race window: virtual time pins the wait, probe counts
 * pin the single re-check.
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

    private fun note(
        durationSeconds: Long?,
        postedAtMs: Long = System.currentTimeMillis(),
        sender: String = "Anna",
    ) = VoiceNoteIdentityExtractor.VoiceNote(
        packageName = "com.whatsapp",
        sender = sender,
        durationSeconds = durationSeconds,
        postedAtMs = postedAtMs,
    )

    @Test
    fun `the probe reads seconds and drops non-positive or failing reads`() {
        assertEquals(42L, VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = 42.5), "/ok"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = 0.0), "/zero"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(returning = -1.0), "/neg"))
        assertNull(VoiceNoteSenderResolution.probeDurationSeconds(preprocessor(throwing = true), "/boom"))
    }

    @Test
    fun `match is free and label-less when nothing is cached`() {
        // The TASK-736 contract: every ordinary share pays nothing when the
        // identity listener is off; the probe must not even run.
        val ap = preprocessor(returning = 43.0)
        assertNull(
            VoiceNoteSenderResolution.match(VoiceNoteIdentityCache(), ap, "com.whatsapp", "/n.opus"))
        verify(exactly = 0) { ap.getAudioDuration(any()) }
    }

    @Test
    fun `match labels a duration-agreeing candidate`() {
        assertEquals(
            "Anna",
            VoiceNoteSenderResolution.match(
                cacheWith(note(durationSeconds = 42L)),
                preprocessor(returning = 42.4),
                "com.whatsapp",
                "/n.opus"))
    }

    @Test
    fun `match stays label-less when the probe fails`() {
        assertNull(
            VoiceNoteSenderResolution.match(
                cacheWith(note(durationSeconds = 42L)),
                preprocessor(throwing = true),
                "com.whatsapp",
                "/n.opus"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an already-cached agreeing note labels on the first attempt`() = runTest {
        // The happy path pays no window: an in-window agreeing candidate
        // labels immediately (one probe, zero virtual time). Pins the
        // attempt-first shape; a wait-then-probe restructure would add 2s
        // to every labeled note and fail the clock assertion.
        val ap = preprocessor(returning = 42.4)
        assertEquals(
            "Anna",
            VoiceNoteSenderResolution.matchForDetection(
                cacheWith(note(durationSeconds = 42L)),
                ap, "com.whatsapp", "/n.opus", detectedAtMs = System.currentTimeMillis()))
        verify(exactly = 1) { ap.getAudioDuration(any()) }
        assertEquals(0L, currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `matchForDetection pays nothing when the identity feature is off`() = runTest {
        // The accepting gate hoisted first: no wait (currentTime pins it),
        // no probe. An unconditional delay would be free under virtual
        // time, so the clock is the assertion.
        val ap = preprocessor(returning = 43.0)
        assertNull(
            VoiceNoteSenderResolution.matchForDetection(
                VoiceNoteIdentityCache(), ap, "com.whatsapp", "/n.opus", detectedAtMs = 1_000L))
        verify(exactly = 0) { ap.getAudioDuration(any()) }
        assertEquals(0L, currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `matchForDetection re-checks once when the feature is on and the cache is still empty`() = runTest {
        // The notification can post after CLOSE_WRITE: with the feature ON
        // and nothing in the event's window, one wait-then-retry. The
        // candidate lands mid-wait (virtual time), so the first check saw
        // nothing (no probe) and only the re-check labels; exactly one
        // window elapses.
        val ap = preprocessor(returning = 42.4)
        val liveCache = cacheWith() // accepting, empty
        backgroundScope.launch {
            delay(1_000)
            liveCache.remember(note(durationSeconds = 42L))
        }
        assertEquals(
            "Anna",
            VoiceNoteSenderResolution.matchForDetection(
                liveCache, ap, "com.whatsapp", "/n.opus", detectedAtMs = System.currentTimeMillis()))
        verify(exactly = 1) { ap.getAudioDuration(any()) }
        assertEquals(2_000L, currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a duration disagreement pays one re-check then stays label-less`() = runTest {
        // The cache holds an in-window note whose marker disagrees and no
        // better one ever lands: the re-check runs (a disagreement is not
        // the note's own notification, review R2-2), probes again, and
        // still returns label-less.
        val ap = preprocessor(returning = 7.0)
        assertNull(
            VoiceNoteSenderResolution.matchForDetection(
                cacheWith(note(durationSeconds = 42L)),
                ap, "com.whatsapp", "/n.opus", detectedAtMs = System.currentTimeMillis()))
        verify(exactly = 2) { ap.getAudioDuration(any()) }
        assertEquals(2_000L, currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a disagreement against another note recovers when the own notification lands`() = runTest {
        // Review R2-2: note A (42s) is cached 20s back; note B (7s) fires
        // its file event and B's notification posts mid-wait. The first
        // check sees only A and disagrees; the re-check sees B and labels.
        val ap = preprocessor(returning = 7.0)
        val liveCache = cacheWith(note(durationSeconds = 42L, postedAtMs = System.currentTimeMillis() - 20_000L))
        backgroundScope.launch {
            delay(1_000)
            liveCache.remember(note(durationSeconds = 7L, sender = "Bob"))
        }
        assertEquals(
            "Bob",
            VoiceNoteSenderResolution.matchForDetection(
                liveCache, ap, "com.whatsapp", "/n.opus", detectedAtMs = System.currentTimeMillis()))
        verify(exactly = 2) { ap.getAudioDuration(any()) }
        assertEquals(2_000L, currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a stale same-length note does not answer for the new note`() = runTest {
        // Review F1: the matcher is duration-based, so yesterday's note of
        // the same length (minutes old, inside the cache TTL but outside
        // the event's window) must not label today's note. One bounded
        // re-check, then label-less; the probe never runs on empty
        // candidates.
        val ap = preprocessor(returning = 42.4)
        val stale = note(durationSeconds = 42L, postedAtMs = System.currentTimeMillis() - 600_000L)
        assertNull(
            VoiceNoteSenderResolution.matchForDetection(
                cacheWith(stale),
                ap, "com.whatsapp", "/n.opus", detectedAtMs = System.currentTimeMillis()))
        verify(exactly = 0) { ap.getAudioDuration(any()) }
        assertEquals(2_000L, currentTime)
    }
}
