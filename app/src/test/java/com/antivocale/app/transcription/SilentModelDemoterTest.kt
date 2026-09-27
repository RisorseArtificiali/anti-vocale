package com.antivocale.app.transcription

import com.antivocale.app.data.FakePreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-675: the silent-model demotion state machine in isolation. Pins the
 * spec rules: two qualifying silent decodes within one session (one demoter
 * instance = one app process), never the first occurrence, never without
 * speech, never on remote/LLM backends, manual selection clears and restarts
 * the counter.
 */
class SilentModelDemoterTest {

    private fun newDemoter(): Pair<SilentModelDemoter, FakePreferencesManager> {
        val prefs = FakePreferencesManager()
        return SilentModelDemoter(prefs) to prefs
    }

    @Test
    fun `first silent decode with speech does not demote`() = runTest {
        val (demoter, _) = newDemoter()
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        assertFalse(demoter.isDemoted("whisper"))
    }

    @Test
    fun `second silent decode with speech in the same session demotes`() = runTest {
        val (demoter, prefs) = newDemoter()
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        assertTrue(demoter.isDemoted("whisper"))
        assertEquals(setOf("whisper"), prefs.demotedBackends.first())
    }

    @Test
    fun `silent decodes without speech never demote`() = runTest {
        val (demoter, _) = newDemoter()
        repeat(5) { demoter.recordSilentDecode("whisper", speechConfirmed = false) }
        assertFalse(demoter.isDemoted("whisper"))
    }

    @Test
    fun `remote and llm backends are never demotable`() = runTest {
        val (demoter, _) = newDemoter()
        assertFalse(SilentModelDemoter.isDemotable(LlmTranscriptionBackend.BACKEND_ID))
        assertFalse(SilentModelDemoter.isDemotable(RemoteOmnivoiceBackend.BACKEND_ID))
        // Even hammered with qualifying observations, nothing lands.
        repeat(5) {
            demoter.recordSilentDecode(LlmTranscriptionBackend.BACKEND_ID, speechConfirmed = true)
            demoter.recordSilentDecode(RemoteOmnivoiceBackend.BACKEND_ID, speechConfirmed = true)
        }
        assertFalse(demoter.isDemoted(LlmTranscriptionBackend.BACKEND_ID))
        assertFalse(demoter.isDemoted(RemoteOmnivoiceBackend.BACKEND_ID))
    }

    @Test
    fun `external and catalog ids are demotable`() = runTest {
        assertTrue(SilentModelDemoter.isDemotable("whisper"))
        assertTrue(SilentModelDemoter.isDemotable("sherpa-onnx"))
        assertTrue(SilentModelDemoter.isDemotable("external:some-uuid"))
    }

    @Test
    fun `demotion is idempotent - extra silent decodes keep the single entry`() = runTest {
        val (demoter, prefs) = newDemoter()
        repeat(4) { demoter.recordSilentDecode("whisper", speechConfirmed = true) }
        assertEquals(setOf("whisper"), prefs.demotedBackends.first())
    }

    @Test
    fun `manual selection clears the demotion and restarts the counter`() = runTest {
        val (demoter, prefs) = newDemoter()
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        assertTrue(demoter.isDemoted("whisper"))

        demoter.onManualSelection("whisper")
        assertFalse(demoter.isDemoted("whisper"))
        assertTrue(prefs.demotedBackends.first().isEmpty())

        // The counter restarted: one more silent decode must not re-demote.
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        assertFalse(demoter.isDemoted("whisper"))
        // A second one does (fresh N=2 after the clear).
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        assertTrue(demoter.isDemoted("whisper"))
    }

    @Test
    fun `counters are per backend - one model's silence never demotes another`() = runTest {
        val (demoter, _) = newDemoter()
        demoter.recordSilentDecode("whisper", speechConfirmed = true)
        demoter.recordSilentDecode("qwen3-asr", speechConfirmed = true)
        assertFalse(demoter.isDemoted("whisper"))
        assertFalse(demoter.isDemoted("qwen3-asr"))
    }

    @Test
    fun `manual selection on a non-demoted backend is a no-op`() = runTest {
        val (demoter, prefs) = newDemoter()
        demoter.onManualSelection("whisper")
        assertTrue(prefs.demotedBackends.first().isEmpty())
        assertFalse(demoter.isDemoted("whisper"))
    }
}
