package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.manager.LlmManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-873 item 1: the long-audio dialog's gate prediction must key the llm
 * row on the SELECTED model path, not the loaded engine (VibeVoice selected
 * while Gemma sits loaded predicted the forced-VAD whole-file path; the real
 * run streams in fixed windows). The external arm already resolves from its
 * record for the same reason (see gateInputsFor's KDoc); this pins the llm
 * arm to the same discipline. The REAL LlmTranscriptionBackend rides a
 * stubbed LlmManager so both arms are pinned against production flags, not a
 * hand-mirrored fake.
 */
class GateInputsForSelectionTest {

    private fun managerWith(
        vararg backends: TranscriptionBackend,
        records: List<com.antivocale.app.data.ExternalModelRecord> = emptyList(),
    ): TranscriptionBackendManager {
        val provider = object : ExternalModelRecordsProvider {
            override val records = MutableStateFlow(records)
        }
        return TranscriptionBackendManager(mockk(relaxed = true), backends.toSet(), provider)
    }

    /** The real llm backend whose LOADED engine path is [loadedPath]. */
    private fun realLlmBackend(loadedPath: String?): LlmTranscriptionBackend {
        val llmManager = mockk<LlmManager> {
            every { getModelPath() } returns loadedPath
        }
        return LlmTranscriptionBackend(llmManager)
    }

    @Test
    fun `selected vibevoice path wins over the loaded gemma engine`() {
        // The bug as filed: VibeVoice SELECTED, Gemma loaded. The loaded
        // engine forces VAD alignment; the selected VibeVoice run must not.
        val manager = managerWith(realLlmBackend(loadedPath = "/models/gemma-4.litertlm"))
        val gates = manager.gateInputsFor(
            LlmTranscriptionBackend.BACKEND_ID,
            selectedModelPath = "/sdcard/VibeVoice-en.litertlm")
        assertEquals(LlmTranscriptionBackend.AUDIO_CHUNK_SECONDS, gates?.maxChunkDurationSeconds)
        assertFalse(gates!!.forcesVadAlignedChunking)
    }

    @Test
    fun `selected gemma path wins over the loaded vibevoice engine`() {
        val manager = managerWith(realLlmBackend(loadedPath = "/sdcard/VibeVoice-en.litertlm"))
        val gates = manager.gateInputsFor(
            LlmTranscriptionBackend.BACKEND_ID,
            selectedModelPath = "/models/gemma-4.litertlm")
        assertTrue(gates!!.forcesVadAlignedChunking)
    }

    @Test
    fun `no selected path keeps the loaded engine's flags`() {
        val manager = managerWith(realLlmBackend(loadedPath = "/models/gemma-4.litertlm"))
        val gates = manager.gateInputsFor(LlmTranscriptionBackend.BACKEND_ID)
        assertTrue(gates!!.forcesVadAlignedChunking)
    }

    @Test
    fun `blank selected path falls back to the loaded engine`() {
        val manager = managerWith(realLlmBackend(loadedPath = "/models/gemma-4.litertlm"))
        val gates = manager.gateInputsFor(LlmTranscriptionBackend.BACKEND_ID, selectedModelPath = " ")
        assertTrue(gates!!.forcesVadAlignedChunking)
    }

    @Test
    fun `non-llm rows ignore the selected path argument`() {
        // A sherpa-family row forces VAD regardless of any path: the selected
        // path argument must only steer the llm row, never other backends.
        val forcedVad = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "parakeet"
            every { requiresVadAlignedChunking } returns true
        }
        val manager = managerWith(realLlmBackend(loadedPath = null), forcedVad)
        val gates = manager.gateInputsFor(
            "parakeet",
            selectedModelPath = "/sdcard/VibeVoice-en.litertlm")
        assertTrue(gates!!.forcesVadAlignedChunking)
        assertNull(manager.gateInputsFor("unregistered-row"))
    }

    @Test
    fun `external record row resolves from its record even with a selected llm path`() {
        // Mirror-arm regression: the external arm stays ahead of the llm arm,
        // and the selected-path argument never leaks into record resolution.
        // Canary is the discriminator (cap 10 + forced VAD; a leaked llm row
        // with a VibeVoice selection would read 30 + no VAD).
        val record = com.antivocale.app.data.ExternalModelRecord(
            id = "canary-1",
            displayName = "Canary import",
            dir = "models/external/canary-1/",
            family = com.antivocale.app.data.ModelFamily.CANARY,
            modelType = "",
            languages = listOf("en"),
            source = com.antivocale.app.data.ExternalModelSource.LOCAL,
            sourceUrl = null,
            files = emptyMap(),
            sizeBytes = 0,
            importedAt = 0)
        val manager = managerWith(realLlmBackend(loadedPath = null), records = listOf(record))
        val gates = manager.gateInputsFor(
            record.backendId,
            selectedModelPath = "/sdcard/VibeVoice-en.litertlm")
        assertEquals(10, gates?.maxChunkDurationSeconds)
        assertTrue(gates!!.forcesVadAlignedChunking)
    }
}
