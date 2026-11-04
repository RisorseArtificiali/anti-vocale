package com.antivocale.app.benchmark

import android.content.Context
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.BackendDescriptor
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.TranscriptionBackend
import com.antivocale.app.transcription.TranscriptionResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * TASK-644: a warm backend must never be benchmarked as-is. Its resident
 * variant/provider/threads are whatever the last load left there, not the
 * config built for the row, and backend.initialize short-circuits on an
 * initialized engine so the exact config would not even apply. The manager
 * must unload a ready engine FIRST, initialize the exact config, and unload
 * again when done (this run owns the engine state).
 */
class BenchmarkManagerResidencyTest {

    private val context = mockk<Context>(relaxed = true)
    private val preferencesManager = mockk<PreferencesManager>(relaxed = true)
    // A SPY of a real registry: name resolution runs the REAL
    // resolveDisplayName derivation (byBackendId defaults to null ->
    // raw displayName fallback, the pre-TASK-955 behavior the residency
    // tests pin), while the busy-message test stubs one lookup.
    private val backendRegistry = io.mockk.spyk(
        BackendRegistry(mockk(relaxed = true), mockk(relaxed = true))) {
        every { byBackendId(any()) } returns null
    }

    private fun backend(ready: Boolean, busy: Boolean = false): TranscriptionBackend = mockk(relaxed = true) {
        io.mockk.every { id } returns "whisper"
        io.mockk.every { displayName } returns "Whisper"
        io.mockk.every { isReady() } returns ready
        io.mockk.every { isBusy() } returns busy
    }

    @Test
    fun `warm engine is displaced before the exact-config benchmark`() = runTest {
        val backend = backend(ready = true)
        val config = BackendConfig.SherpaOnnxConfig(
            modelDir = "/models/whisper-b", modelType = "whisper", numThreads = 4)
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = ""))

        val manager = BenchmarkManager(context, preferencesManager, backendRegistry)
        val result = manager.runBenchmark(backend, config)

        assertTrue(result.isSuccess)
        coVerifyOrder {
            backend.isReady()
            backend.unload()
            backend.initialize(context, config)
            backend.transcribeAudio(any(), any(), any())
            backend.unload()
        }
    }

    @Test
    fun `cold engine is initialized and unloaded without a displacement`() = runTest {
        val backend = backend(ready = false)
        val config = BackendConfig.SherpaOnnxConfig(
            modelDir = "/models/whisper-b", modelType = "whisper", numThreads = 4)
        coEvery { backend.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = ""))

        val manager = BenchmarkManager(context, preferencesManager, backendRegistry)
        val result = manager.runBenchmark(backend, config)

        assertTrue(result.isSuccess)
        coVerifyOrder {
            backend.initialize(context, config)
            backend.transcribeAudio(any(), any(), any())
            backend.unload()
        }
        // No displacement unload happened on a cold engine.
        io.mockk.verify(exactly = 1) { backend.unload() }
    }
    @Test
    fun `busy engine is refused without displacement or initialization`() = runTest {
        val backend = backend(ready = true, busy = true)
        val config = BackendConfig.SherpaOnnxConfig(
            modelDir = "/models/whisper-b", modelType = "whisper", numThreads = 4)

        val manager = BenchmarkManager(context, preferencesManager, backendRegistry)
        val result = manager.runBenchmark(backend, config)

        assertTrue(result.isFailure)
        // Neither the displacement unload nor initialize ran: the native
        // recognizer stays untouched under the in-flight decode.
        io.mockk.verify(exactly = 0) { backend.unload() }
        coVerifyOrder(inverse = true) { backend.initialize(any(), any()) }
    }

    /**
     * TASK-955: the busy-state message names the backend through the
     * registry resolve, not the raw displayName literal. The registry's
     * OTHER methods are stubbed but [BackendRegistry.resolveDisplayName]
     * itself runs REAL (a descriptor whose name derives from the saved
     * path, no Context resources involved), so the wiring the message
     * depends on - lookup, path read, variantAwareDisplayName - is
     * exercised, per the TASK-957 review's tautology note.
     */
    @Test
    fun `busy message names the backend through the registry`() = runTest {
        val descriptor = BackendDescriptor(
            backendId = "sherpa-onnx",
            shareAlias = "",
            modelPathFlow = { kotlinx.coroutines.flow.flowOf("/models/whisper-b") },
            saveModelPath = { _, _ -> },
            clearModelPath = { _ -> },
        )
        coEvery { backendRegistry.byBackendId("whisper") } returns descriptor

        val backend = backend(ready = true, busy = true)
        val config = BackendConfig.SherpaOnnxConfig(
            modelDir = "/models/whisper-b", modelType = "whisper", numThreads = 4)

        val manager = BenchmarkManager(context, preferencesManager, backendRegistry)
        val result = manager.runBenchmark(backend, config)

        assertTrue(result.isFailure)
        // deriveDisplayName's default is the path's file name: the message
        // must carry the DERIVED name, proving the whole resolve ran.
        assertEquals(
            "A transcription is running on whisper-b; benchmark it again when idle",
            result.exceptionOrNull()?.message)
    }
}
