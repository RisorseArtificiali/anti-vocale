package com.antivocale.app.transcription

import com.antivocale.app.manager.EngineWedgeTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-664 (GH #119): the empty-chunk recovery ladder, exercised without the
 * native engine through the decode lambda (the vocaphone arrangement). The
 * caller's FIRST decode is outside the ladder; every test feeds it a chunk
 * that already decoded blank.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptyChunkRecoveryTest {

    private val rate = 16_000

    /** One second of ones; the values only make feeds distinguishable. */
    private fun chunk(value: Float = 1f, seconds: Int = 1) = FloatArray(rate * seconds) { value }

    private fun token(text: String, startMs: Long, endMs: Long) = TimedToken(text, startMs, endMs)

    @Test
    fun `a fresh decode of the same samples recovers the chunk`() = runTest {
        val feeds = mutableListOf<FloatArray>()
        val outcome = EmptyChunkRecovery.recover(chunk(1f), rate, null, null) { feed ->
            feeds.add(feed.copyOf())
            Result.success(TranscriptionResult(text = "recovered"))
        }
        assertEquals("recovered", (outcome as EmptyChunkRecovery.Outcome.Recovered).result.text)
        // Rung 1 re-feeds the SAME samples, never a padded or overlapped copy.
        assertEquals(listOf(rate), feeds.map { it.size })
        assertTrue(feeds[0].contentEquals(chunk(1f)))
    }

    @Test
    fun `a chunk still empty after the ladder stays an honest blank`() = runTest {
        var calls = 0
        val outcome = EmptyChunkRecovery.recover(
            chunk(1f), rate,
            previousChunk = chunk(2f),
            nextChunk = chunk(3f)) { _ ->
            calls++
            Result.success(TranscriptionResult(text = ""))
        }
        assertEquals(EmptyChunkRecovery.Outcome.StillEmpty, outcome)
        // Bounded: first rung plus the overlap rung, nothing more.
        assertEquals(EmptyChunkRecovery.MAX_EXTRA_DECODES, calls)
    }

    @Test
    fun `the overlap rung trims neighbor words and rebases token times`() = runTest {
        val feeds = mutableListOf<Int>()
        // Rung 1 blank; the overlap decode hears all three windows.
        var calls = 0
        val outcome = EmptyChunkRecovery.recover(
            chunk(1f), rate,
            previousChunk = chunk(2f),
            nextChunk = chunk(3f)) { feed ->
            feeds.add(feed.size)
            if (++calls == 1) return@recover Result.success(TranscriptionResult(text = ""))
            Result.success(TranscriptionResult(
                text = "A B C D",
                tokens = listOf(
                    // prev-window word (before 1000ms): dropped
                    token("▁A", 500, 600),
                    // chunk-window words (1000..2000ms): kept, rebased by 1000
                    token("▁B", 1200, 1300),
                    token("▁C", 1800, 1900),
                    // next-window word (from 2000ms): dropped
                    token("▁D", 2600, 2700),
                )))
        }
        val recovered = (outcome as EmptyChunkRecovery.Outcome.Recovered).result
        assertEquals("B C", recovered.text)
        assertEquals(listOf(200L to 300L, 800L to 900L), recovered.tokens.map { it.startMs to it.endMs })
        // prevTail(1s) + chunk(1s) + nextHead(1s)
        assertEquals(listOf(rate, rate * 3), feeds)
    }

    @Test
    fun `the first chunk trims with next head overlap only`() = runTest {
        val feeds = mutableListOf<Int>()
        val outcome = EmptyChunkRecovery.recover(
            chunk(1f), rate,
            previousChunk = null,
            nextChunk = chunk(3f)) { feed ->
            feeds.add(feed.size)
            if (feed.size == rate) return@recover Result.success(TranscriptionResult(text = ""))
            Result.success(TranscriptionResult(
                text = "B C",
                tokens = listOf(token("▁B", 500, 600), token("▁C", 1800, 1900))))
        }
        val recovered = (outcome as EmptyChunkRecovery.Outcome.Recovered).result
        // Window is [0, 1000): B kept, C (next-head audio) dropped.
        assertEquals("B", recovered.text)
        assertEquals(listOf(rate, rate * 2), feeds)
    }

    @Test
    fun `the last chunk trims with previous tail overlap only`() = runTest {
        val feeds = mutableListOf<Int>()
        val outcome = EmptyChunkRecovery.recover(
            chunk(1f), rate,
            previousChunk = chunk(2f),
            nextChunk = null) { feed ->
            feeds.add(feed.size)
            if (feed.size == rate) return@recover Result.success(TranscriptionResult(text = ""))
            Result.success(TranscriptionResult(
                text = "A B",
                tokens = listOf(token("▁A", 500, 600), token("▁B", 1500, 1600))))
        }
        val recovered = (outcome as EmptyChunkRecovery.Outcome.Recovered).result
        // Window is [1000, 2000): A (prev-tail audio) dropped, B kept and rebased.
        assertEquals("B", recovered.text)
        assertEquals(listOf(500L), recovered.tokens.map { it.startMs })
        assertEquals(listOf(rate, rate * 2), feeds)
    }

    @Test
    fun `overlap text without token timestamps is never adopted`() = runTest {
        // Unattributable words would duplicate the neighbors' transcripts.
        val outcome = EmptyChunkRecovery.recover(
            chunk(1f), rate,
            previousChunk = chunk(2f),
            nextChunk = chunk(3f)) { feed ->
            if (feed.size == rate) return@recover Result.success(TranscriptionResult(text = ""))
            Result.success(TranscriptionResult(text = "neighbor words"))
        }
        assertEquals(EmptyChunkRecovery.Outcome.StillEmpty, outcome)
    }

    @Test
    fun `a chunk with no neighbors is re-fed with silence padding`() = runTest {
        val feeds = mutableListOf<Int>()
        val outcome = EmptyChunkRecovery.recover(chunk(1f), rate, null, null) { feed ->
            feeds.add(feed.size)
            if (feed.size == rate) return@recover Result.success(TranscriptionResult(text = ""))
            // Half a second of pad ahead: 600ms in the feed is 100ms into the chunk.
            Result.success(TranscriptionResult(
                text = "short note",
                tokens = listOf(token("▁short", 600, 900))))
        }
        val recovered = (outcome as EmptyChunkRecovery.Outcome.Recovered).result
        assertEquals("short note", recovered.text)
        assertEquals(listOf(100L), recovered.tokens.map { it.startMs })
        // Same samples first, then pad + chunk + pad.
        assertEquals(listOf(rate, rate + 2 * (rate * EmptyChunkRecovery.PAD_SECONDS).toInt()), feeds)
    }

    @Test
    fun `a wedged rung aborts the ladder instead of spending another ceiling`() = runTest {
        var calls = 0
        val outcome = EmptyChunkRecovery.recover(chunk(1f), rate, null, null) { _ ->
            calls++
            Result.failure(EngineWedgeTimeoutException("generation timed out"))
        }
        val error = (outcome as EmptyChunkRecovery.Outcome.EngineWedged).error
        assertEquals("generation timed out", error.message)
        assertEquals(1, calls)
    }

    @Test
    fun `a thrown rung error is spent, not fatal`() = runTest {
        var calls = 0
        val outcome = EmptyChunkRecovery.recover(chunk(1f), rate, null, null) { feed ->
            if (++calls == 1) throw IllegalStateException("transient native error")
            Result.success(TranscriptionResult(text = "second try"))
        }
        assertEquals("second try", (outcome as EmptyChunkRecovery.Outcome.Recovered).result.text)
    }

    @Test
    fun `an empty chunk never enters the ladder`() = runTest {
        var calls = 0
        val outcome = EmptyChunkRecovery.recover(FloatArray(0), rate, chunk(2f), chunk(3f)) { _ ->
            calls++
            Result.success(TranscriptionResult(text = "unreachable"))
        }
        assertEquals(EmptyChunkRecovery.Outcome.StillEmpty, outcome)
        assertEquals(0, calls)
    }

    @Test
    fun `tail and head borrow at most the overlap seconds`() = runTest {
        val neighbor = chunk(9f, seconds = 30)
        assertEquals(rate * EmptyChunkRecovery.NEIGHBOR_OVERLAP_SECONDS, EmptyChunkRecovery.tail(neighbor, rate).size)
        assertEquals(rate * EmptyChunkRecovery.NEIGHBOR_OVERLAP_SECONDS, EmptyChunkRecovery.head(neighbor, rate).size)
        // A neighbor shorter than the bound lends its whole self.
        assertEquals(100, EmptyChunkRecovery.head(FloatArray(100), rate).size)
        assertEquals(100, EmptyChunkRecovery.tail(FloatArray(100), rate).size)
    }
}
