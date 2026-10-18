package com.antivocale.app.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-853 slice 2: the WhistleBackend's JVM-reachable contract. The
 * engine itself is device territory (the bridge loads libneedle); these
 * pin the surface the orchestrator touches: whole-clip declaration, the
 * config-type guard, the audio-only arm, and the dormant-registration
 * invariant (the backend exists in the DI set BEFORE its catalog entry:
 * WHISTLE is defined but not yet in ALL, so selectability and the
 * catalog pin stay unchanged until slice 3's entry lands).
 */
class WhistleBackendTest {

    private val backend = WhistleBackend(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `the backend declares whole-clip audio with no special chunking`() {
        assertEquals(BuiltInBackendIds.WHISTLE, backend.id)
        assertTrue(backend.supportsAudio)
        assertFalse(backend.supportsText)
        // The engine windows past 30s internally: null means the
        // orchestrator never splits what the engine splits better.
        assertEquals(null, backend.maxChunkDurationSeconds)
        assertFalse(backend.requiresVadAlignedChunking)
    }

    @Test
    fun `initialize rejects any config but WhistleConfig`() {
        val wrong = kotlinx.coroutines.runBlocking {
            backend.initialize(
                io.mockk.mockk(relaxed = true),
                BackendConfig.SherpaOnnxConfig(
                    modelDir = "/x", modelType = "nemo_transducer",
                    numThreads = 4))
        }
        // The JVM-unavailable engine fails FIRST on a non-arm host or the
        // stub, but the config-type guard is checkable everywhere: the
        // failure must name the config type, not crash.
        assertTrue(wrong.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `generateText is the honest audio-only failure`() {
        val result = kotlinx.coroutines.runBlocking { backend.generateText("hi") }
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `whistle is registered dormant, id defined but not yet selectable`() {
        // Slice 3's catalog entry flips this; until then the backend
        // exists in the DI set without surfacing anywhere.
        assertFalse(BuiltInBackendIds.WHISTLE in BuiltInBackendIds.ALL)
        assertFalse(BuiltInBackendIds.isSelectableBackendId(BuiltInBackendIds.WHISTLE))
        // And no registry descriptor either (the one ghost-row path the
        // ALL pin does not cover: a descriptor appearing before the flip).
        // Same fixture shape as BackendRegistryTest's.
        val store = com.antivocale.app.data.ExternalModelStore(
            com.antivocale.app.data.FakePreferencesManager(),
            dirExists = { true })
        val registry = BackendRegistry(store, object : com.antivocale.app.data.ExternalModelRecordsProvider {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.antivocale.app.data.ExternalModelRecord>())
        })
        org.junit.Assert.assertNull(registry.byBackendId(BuiltInBackendIds.WHISTLE))
    }
}
