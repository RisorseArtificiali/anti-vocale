package com.antivocale.app.transcription

import com.antivocale.app.data.FakePreferencesManager
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
 * config-type guard, and the audio-only arm (slice 3 shipped: WHISTLE is
 * selectable and cataloged). The keyword-biasing parse runs behind the
 * loaded engine, so it is device territory, not JVM-pinned.
 */
class WhistleBackendTest {

    private val backend = WhistleBackend(
        CoroutineScope(Dispatchers.Unconfined),
        FakePreferencesManager())

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
    fun `whistle is selectable and carries a registry descriptor, slice 3 active`() {
        // The catalog entry landed: the id joined ALL (the catalog pin
        // BundledModelCatalogTest enforces the same-commit coupling) and
        // the registry builds its descriptor.
        assertTrue(BuiltInBackendIds.WHISTLE in BuiltInBackendIds.ALL)
        assertTrue(BuiltInBackendIds.isSelectableBackendId(BuiltInBackendIds.WHISTLE))
        // staticRegistry() seeds the bundled catalog (a hand-built registry
        // would throw on first descriptor access in an isolated JVM run -
        // the review's isolation catch).
        org.junit.Assert.assertNotNull(staticRegistry().byBackendId(BuiltInBackendIds.WHISTLE))
    }
}
