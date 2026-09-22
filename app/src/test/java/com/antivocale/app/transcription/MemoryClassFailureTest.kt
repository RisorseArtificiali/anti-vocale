package com.antivocale.app.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-625: the memory-class classifier that gates the error notification's
 * Memory-protection action. Calls the production function the orchestrator
 * calls at its onError sites; detection is typed, never the message text.
 */
class MemoryClassFailureTest {

    @Test
    fun `pre-flight refusals are memory class`() {
        assertTrue(
            isMemoryClassFailure(
                TranscriptionException.InsufficientMemory("avail 1GB, required 2GB")))
    }

    @Test
    fun `the OOM catch is memory class`() {
        assertTrue(
            isMemoryClassFailure(OutOfMemoryError("Failed to allocate a 83886016 byte allocation")))
    }

    @Test
    fun `model load errors are not memory class`() {
        assertFalse(
            isMemoryClassFailure(TranscriptionException.ModelLoadError("directory not found")))
    }

    @Test
    fun `native and generic errors are not memory class`() {
        assertFalse(isMemoryClassFailure(TranscriptionException.NativeError("JNI crash")))
        assertFalse(isMemoryClassFailure(IllegalStateException("No Whisper model configured")))
        assertFalse(isMemoryClassFailure(RuntimeException()))
    }

    @Test
    fun `memory-sounding prose is not memory class without the type`() {
        assertFalse(
            isMemoryClassFailure(
                IllegalStateException("Not enough free memory to transcribe with this model")))
    }
}
