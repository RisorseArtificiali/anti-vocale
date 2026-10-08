package com.antivocale.app.service

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opentranscribe.api.ErrorType

/**
 * TASK-785: the Open Transcribe contract mapping in
 * [OpenTranscribeCallbackBridge], pinned against the orchestrator's real
 * emit shapes (see the bridge KDoc for the emit-site survey): per-chunk
 * delta interims from the multi-chunk arms, revising partials from the
 * single-chunk streaming arm, segments before the terminal result, the
 * typed error mapping, and exactly one terminal delivery.
 */
class OpenTranscribeCallbackBridgeTest {

    private class RecordingEmitter : OpenTranscribeEmitter {
        val progress = mutableListOf<String>()
        val segments = mutableListOf<Triple<Long, Long, String>>()
        var result: String? = null
        var error: Pair<Byte, String?>? = null
        var resultBeforeAnySegment = false

        override fun onProgress(text: String) {
            progress += text
        }

        override fun onSegment(startMs: Long, endMs: Long, text: String) {
            // The contract sends segments just before the result; a result
            // already delivered means the order broke.
            if (result != null) resultBeforeAnySegment = true
            segments += Triple(startMs, endMs, text)
        }

        override fun onResult(text: String) {
            result = text
        }

        override fun onError(type: Byte, message: String?) {
            error = type to message
        }
    }

    private fun bridge(emitter: RecordingEmitter) = OpenTranscribeCallbackBridge(emitter)

    // ---- progress accumulation ----

    @Test
    fun `multi-chunk deltas accumulate into cumulative progress`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onInterimResult("uno", "uno", "Chunk 1/3", 0, "uno", 3)
        bridge.onInterimResult("due", "due", "Chunk 2/3", 1, "due", 3)
        bridge.onInterimResult("tre", "tre", "Chunk 3/3", 2, "tre", 3)
        assertEquals(listOf("uno", "uno due", "uno due tre"), emitter.progress)
    }

    @Test
    fun `streaming partial revisions replace the same chunk instead of appending`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onInterimResult("Hel", "Hel", "", 0, "Hel", 1)
        bridge.onInterimResult("Hello", "Hello", "", 0, "Hello", 1)
        bridge.onInterimResult("Hello world", "Hello world", "", 0, "Hello world", 1)
        assertEquals(listOf("Hel", "Hello", "Hello world"), emitter.progress)
    }

    @Test
    fun `revised chunk keeps its position in the accumulated text`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onInterimResult("a", "a", "", 0, "a", 2)
        bridge.onInterimResult("b", "b", "", 1, "b", 2)
        // A later revision of chunk 0 must not jump behind chunk 1.
        bridge.onInterimResult("A!", "A!", "", 0, "A!", 2)
        assertEquals(listOf("a", "a b", "A! b"), emitter.progress)
    }

    @Test
    fun `blank bigText falls back to contentText and fully blank emits nothing`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onInterimResult("keep", "", "", 0, null, 1)
        bridge.onInterimResult("", "   ", "", 1, null, 2)
        assertEquals(listOf("keep"), emitter.progress)
    }

    @Test
    fun `emissions without chunk identity pass through unaccumulated`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onInterimResult("x", "x", "", -1, null, 0)
        bridge.onInterimResult("y", "y", "", -1, null, 0)
        assertEquals(listOf("x", "y"), emitter.progress)
    }

    // ---- success terminal ----

    @Test
    fun `segments arrive before the result and the result stays the stored text without speakers`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        val cues = listOf(
            TimedSegment(0, 1500, "hello there"),
            TimedSegment(1500, 3000, "general"),
        )
        bridge.onSuccess("t1", "hello there general", false, null, 10, null, null, false, 0, false, cues, null, false)
        assertEquals(2, emitter.segments.size)
        assertEquals(Triple(0L, 1500L, "hello there"), emitter.segments[0])
        assertEquals(Triple(1500L, 3000L, "general"), emitter.segments[1])
        assertEquals("hello there general", emitter.result)
        assertEquals(false, emitter.resultBeforeAnySegment)
    }

    @Test
    fun `a diarized result carries the speaker-annotated form every surface delivers`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        val cues = listOf(
            TimedSegment(0, 1500, "hello there", speaker = 0),
            TimedSegment(1500, 3000, "general", speaker = 1),
        )
        bridge.onSuccess("t1", "hello there general", false, null, 10, null, null, false, 0, false, cues, null, false)
        assertEquals("SPEAKER 1: hello there\nSPEAKER 2: general", emitter.result)
        // Cue text stays plain: the turn-start prefix is a rendering decision.
        assertEquals("hello there", emitter.segments[0].third)
    }

    // ---- error mapping ----

    @Test
    fun `no-model failures map to MODEL_NOT_AVAILABLE`() {
        val emitter = RecordingEmitter()
        bridge(emitter).onError("t", "BACKEND_LOAD_FAILED", "no model", false, true, 5, false, false)
        assertEquals(ErrorType.MODEL_NOT_AVAILABLE, emitter.error!!.first)
    }

    @Test
    fun `decode failures map to DECODE_FAILED with the message`() {
        val emitter = RecordingEmitter()
        bridge(emitter).onError("t", "INFERENCE_ERROR", "no decoder", false, false, 5, false, true)
        val error = emitter.error!!
        assertEquals(ErrorType.DECODE_FAILED, error.first)
        assertEquals("no decoder", error.second)
    }

    @Test
    fun `everything else maps to UNEXPECTED`() {
        val emitter = RecordingEmitter()
        bridge(emitter).onError("t", "OUT_OF_MEMORY", "low memory", false, false, 5, true, false)
        assertEquals(ErrorType.UNEXPECTED, emitter.error!!.first)
    }

    // ---- exactly one terminal ----

    @Test
    fun `cancellation after an error is dropped`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onError("t", "INFERENCE_ERROR", "boom", false, false, 5, false, false)
        bridge.deliverCancelled()
        assertEquals(ErrorType.UNEXPECTED, emitter.error!!.first)
        assertNull(emitter.result)
    }

    @Test
    fun `an error after a result is dropped`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.onSuccess("t", "text", false, null, 1, null, null, false, 0, false, emptyList(), null, false)
        bridge.onError("t", "PROCESSING_ERROR", "late", false, false, 1, false, false)
        assertEquals("text", emitter.result)
        assertNull(emitter.error)
    }

    @Test
    fun `cancellation is delivered once and carries no message`() {
        val emitter = RecordingEmitter()
        val bridge = bridge(emitter)
        bridge.deliverCancelled()
        bridge.deliverCancelled()
        assertEquals(ErrorType.CANCELLED, emitter.error!!.first)
        assertNull(emitter.error!!.second)
        assertNull(emitter.result)
    }
}
