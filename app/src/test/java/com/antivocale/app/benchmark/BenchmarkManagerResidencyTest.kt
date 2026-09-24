package com.antivocale.app.benchmark

import android.content.Context
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.BackendConfig
import com.antivocale.app.transcription.TranscriptionBackend
import com.antivocale.app.transcription.TranscriptionResult
import io.mockk.coEvery
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

        val manager = BenchmarkManager(context, preferencesManager)
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

        val manager = BenchmarkManager(context, preferencesManager)
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

        val manager = BenchmarkManager(context, preferencesManager)
        val result = manager.runBenchmark(backend, config)

        assertTrue(result.isFailure)
        // Neither the displacement unload nor initialize ran: the native
        // recognizer stays untouched under the in-flight decode.
        io.mockk.verify(exactly = 0) { backend.unload() }
        coVerifyOrder(inverse = true) { backend.initialize(any(), any()) }
    }
}
