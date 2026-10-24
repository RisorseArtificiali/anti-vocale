package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-874: pins the streaming decode contract the OnlineRecognizer is built
 * with. The desktop A/B (TASK-530, 2026-10-24) chose modified_beam_search over
 * greedy for the Nemotron streaming family (WER 9.3% vs 10.3% at +13-20%
 * wall); a silent revert to greedy, or an accidental beam-width change, must
 * fail here rather than ride an unmeasured default. The device A/B leg gates
 * this value before the change ships.
 */
class SherpaBackendStreamingDecodeTest {

    @Test
    fun `the streaming recognizer decodes with modified beam search`() {
        assertEquals("modified_beam_search", SherpaBackend.STREAMING_DECODING_METHOD)
    }

    @Test
    fun `the streaming beam width matches the arm that won`() {
        assertEquals(4, SherpaBackend.STREAMING_MAX_ACTIVE_PATHS)
    }
}
