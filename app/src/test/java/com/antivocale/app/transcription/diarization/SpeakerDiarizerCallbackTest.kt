package com.antivocale.app.transcription.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the contract documented on SpeakerDiarizer.progressCallback (device crash 2026-11-27, TASK-1060.5). */
class SpeakerDiarizerCallbackTest {

    @Test
    fun `the progress callback carries the specialized invoke the sherpa JNI resolves`() {
        val callback = SpeakerDiarizer.progressCallback { _, _ -> }
        val method = callback.javaClass.getMethod(
            "invoke", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        assertEquals(java.lang.Integer::class.java, method.returnType)
    }

    @Test
    fun `the callback forwards progress and swallows a listener failure`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        val forwarding = SpeakerDiarizer.progressCallback { done, total -> seen += done to total }
        assertEquals(0, forwarding(3, 7, 0L))
        assertEquals(listOf(3 to 7), seen)

        val failing = SpeakerDiarizer.progressCallback { _, _ -> error("listener") }
        assertEquals(0, failing(1, 2, 0L))
    }
}
