package com.antivocale.app.transcription

import android.content.Context
import java.util.concurrent.CountDownLatch

/**
 * TASK-391 stand-in backend for the out-of-order chunk test. The interleaving is
 * forced deterministically with latches (the guide's stand-in clause: injecting
 * into test-owned code adds nothing over explicit coordination): the FIRST chunk
 * to enter transcribeAudio blocks until the SECOND has fully completed, so with
 * the in-flight limit raised to 2 (production is serial, TASK-406; the ordering
 * test raises the orchestrator seam) completion order is guaranteed [2, 1] and
 * the orchestrator's results assembly must still join in chunk order.
 */
class ChunkOrderingFakeBackend : TranscriptionBackend {

    /** Armed by the test: the first chunk to enter blocks until secondDone opens. */
    @Volatile var forceOutOfOrder = false
    private val secondDone = CountDownLatch(1)

    /** 1-based indices in COMPLETION order; the test asserts [2, 1]. */
    val completionOrder = mutableListOf<Int>()

    /** Set by the blocked first chunk right before it waits (test health check). */
    @Volatile var firstBlocked = false

    // "whisper": matches the preferred backend id so ensureBackendLoaded does not
    // try to reload a real catalog backend over the fake (ParallelTest pattern).
    override val id = "whisper"
    override val displayName = "ChunkOrderingFake"
    override val supportsAudio = true
    override val supportsText = false
    override val maxChunkDurationSeconds: Int = 30

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        // Identity from the PAYLOAD, not the entry order: whichever async enters
        // first must not claim a name, or the join assertion would race.
        val chunkId = if (samples.isNotEmpty() && samples[0] == 0.1f) 1 else 2
        if (forceOutOfOrder && chunkId == 1) {
            firstBlocked = true
            // Bounded wait: chunk 2 must complete while chunk 1 holds its
            // semaphore slot; the 10s cap keeps a broken test finite.
            check(secondDone.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "chunk 2 never completed while chunk 1 was blocked"
            }
        }
        val result = Result.success(TranscriptionResult(text = "chunk-$chunkId"))
        synchronized(completionOrder) { completionOrder.add(chunkId) }
        if (forceOutOfOrder && chunkId == 2) secondDone.countDown()
        return result
    }

    override suspend fun initialize(context: Context, config: BackendConfig) = Result.success(Unit)
    override suspend fun generateText(prompt: String): Result<String> =
        Result.failure(IllegalStateException("text not supported"))
    override fun isReady() = true
    override fun isAudioSupported() = true
    override fun unload() {}
    override fun setKeepAliveTimeout(minutes: Int) {}
    override fun getModelPath(): String? = null
}
