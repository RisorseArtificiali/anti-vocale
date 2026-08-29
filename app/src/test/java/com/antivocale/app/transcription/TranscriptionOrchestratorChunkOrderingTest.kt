package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-391: the parallel chunk path (VAD-segmented, progressive off) with the
 * in-flight limit raised to 2. The fake backend blocks the FIRST chunk until the
 * second has completed, so completion order is [2, 1] by construction; the
 * results assembly must still join in CHUNK order and the accounting must stay
 * exact. Deterministic without injection (stand-in clause of the guide): the
 * coordination lives in the test-owned backend, so no -Pbyteman gate is needed.
 */
class TranscriptionOrchestratorChunkOrderingTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: ChunkOrderingFakeBackend

    override fun baseSetUp() {
        super.baseSetUp()
        backend = ChunkOrderingFakeBackend()
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        // VAD-segmented + progressive OFF routes to the PARALLEL chunk path.
        every { preferencesManager.transcriptionBackend } returns flowOf("whisper")
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf("/models/whisper")
        every { preferencesManager.vadEnabled } returns flowOf(true)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.defaultPrompt } returns flowOf("")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        coEvery { transcriptionCalibrator.getEstimate(any(), any()) } returns null
    }

    private fun stubTwoVadChunks() {
        val chunks = listOf(FloatArray(100) { 0.1f }, FloatArray(100) { 0.2f })
        every {
            audioPreprocessor.prepareAudioForMediaPipe(
                inputPath = any(), cacheDir = any(), maxChunkDurationSeconds = any(),
                context = any(), enableVad = any(), vadNumThreads = any(), vadProvider = any())
        } returns AudioPreprocessor.PreprocessingResult(
            chunks = chunks, sampleRate = 16000,
            totalDurationSeconds = 60.0, chunkCount = 2, isVadSegmented = true)
    }

    private suspend fun CoroutineScope.runRequest(requestScope: CoroutineScope = this): Result<String> {
        // Same pattern as TranscriptionOrchestratorParallelTest: creating the file
        // inside the rule folder also forces the folder into existence first.
        val audioFile = java.io.File(temporaryFolder.newFolder(), "audio.wav")
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        // withContext(Default): processParallelChunks' async bodies inherit the
        // CALLER context; from runBlocking they would serialize on its single
        // event-loop thread and deadlock on the latch.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            orchestrator.processRequest(
            taskId = "ordering-task", requestType = "audio", prompt = "",
            filePath = audioFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk<Context>(relaxed = true),
            cacheDir = temporaryFolder.root,
                listener = listener, coroutineScope = requestScope)
        }
    }

    @Test
    fun `out-of-order completion still joins in chunk order`() = kotlinx.coroutines.runBlocking {
        // Production runs chunks serially (TASK-406: peak memory multiplies per in-flight
        // chunk), which makes out-of-order completion impossible; the join-by-index
        // property this test guards only exists above 1 permit, so raise it here.
        orchestrator.maxConcurrentChunks = 2
        stubTwoVadChunks()
        backend.forceOutOfOrder = true

        // The chunks must run on REAL parallel threads (runTest's virtual-time
        // single thread would deadlock on the latch): multi-threaded scope.
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job())
        val result = runRequest(scope)

        assertTrue("expected success, got: ${result.exceptionOrNull()}", result.isSuccess)
        // Health check (the guide's trap-2 equivalent): the blocking must have
        // actually happened, or the out-of-order premise is not proven.
        assertTrue("first chunk never blocked: out-of-order premise not established", backend.firstBlocked)
        // The out-of-order premise actually held: chunk 2 completed before chunk 1.
        assertEquals(listOf(2, 1), backend.completionOrder)
        // The join is by CHUNK index, not completion order.
        assertEquals("chunk-1 chunk-2", result.getOrNull())
        scope.cancel()
    }
}
