package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-1033: the built-in OFFLINE decode must feed each OfflineStream
 * exactly once, tail pad included. sherpa's OfflineStream finalizes the
 * feature extractor inside every acceptWaveform (offline-stream.cc calls
 * fbank_->InputFinished() per feed), so a second feed aborts the process
 * natively ("AcceptWaveform called after InputFinished() was called.";
 * deterministic on the RMX3853 with the parakeet tail pad, sherpa 2.0.2,
 * pre-existing in v1.17.0). The sherpa Kotlin types load native JNI, so
 * the host-testable surface is the pure [SherpaBackend.offlineFeed]
 * builder: the padded entry's feed carries the pad as trailing samples of
 * ONE array (the call site feeds it with a single acceptWaveform; gigaam,
 * the padded built-in, is the witness entry the arithmetic mirrors).
 */
class SherpaBackendOfflineFeedTest {

    @Test
    fun `a padded entry's feed carries the tail pad inside one array`() {
        val samples = FloatArray(1600) { it * 0.01f } // 100ms at 16kHz
        // gigaam: tailPadSeconds=1 in the bundled catalog.
        val feed = SherpaBackend.offlineFeed(samples, 16000, 1.0, TailSilenceBuffer())
        assertEquals("audio + the 1s pad, concatenated", samples.size + 16000, feed.size)
        assertTrue("the audio leads the pad unchanged", feed.copyOfRange(0, samples.size).contentEquals(samples))
        assertEquals("the pad is silence", 0f, feed[samples.size])
    }

    @Test
    fun `an unpadded entry's feed is the samples untouched`() {
        val samples = FloatArray(1600)
        // assertSame, not assertEquals: the pinned property is that the
        // unpadded arm pays no copy (JUnit's assertEquals(Object,Object)
        // would quietly degrade to identity anyway).
        org.junit.Assert.assertSame(
            samples, SherpaBackend.offlineFeed(samples, 16000, 0.0, TailSilenceBuffer()))
    }
}
