package com.antivocale.app.transcription

import org.junit.Assert.*
import org.junit.Test

/**
 * GH #49: the model selection UI must declare each model's audio limit BEFORE
 * download, derived from metadata only (no per-model hardcoded UI strings).
 */
class ModelAudioLimitTest {

    @Test
    fun `hard cap applies when a max duration is declared without chunking`() {
        // Gemma: 30s hard limit, no chunking
        assertEquals(AudioLimit.HardCap(30), audioLimit(maxAudioDuration = 30, chunkDurationSeconds = 0))
    }

    @Test
    fun `chunking wins over the per-segment cap`() {
        // Whisper/Qwen3 declare BOTH maxAudioDuration=30 and 30s chunks: with
        // software chunking any length is accepted, so the cap is not user-facing
        assertEquals(AudioLimit.ChunkedAnyLength, audioLimit(maxAudioDuration = 30, chunkDurationSeconds = 30))
    }

    @Test
    fun `chunked models are any-length`() {
        // Parakeet (60s chunks since TASK-406; the model's own attention cap is 400s)
        assertEquals(AudioLimit.ChunkedAnyLength, audioLimit(null, 60))
    }

    @Test
    fun `models without limits report no known limit`() {
        // GigaAM (offline transducer, no cap), Nemotron (streaming)
        assertEquals(AudioLimit.NoKnownLimit, audioLimit(null, 0))
    }
}
