package com.antivocale.app.transcription

import org.junit.Assert.*
import org.junit.Test

class InferenceProviderTest {

    // ---- resolve() ----

    @Test
    fun `resolve CPU returns CPU`() {
        assertEquals("cpu", InferenceProvider.resolve("cpu"))
    }

    @Test
    fun `resolve NNAPI returns NNAPI`() {
        assertEquals("nnapi", InferenceProvider.resolve("nnapi"))
    }

    @Test
    fun `resolve auto falls back to CPU on JVM tests`() {
        // Build.VERSION.SDK_INT is 0 in plain JVM tests, so isNnapiAvailable() = false
        assertEquals("cpu", InferenceProvider.resolve("auto"))
    }

    @Test
    fun `resolve unknown value falls back to CPU on JVM tests`() {
        assertEquals("cpu", InferenceProvider.resolve("something_else"))
    }

    @Test
    fun `resolve empty string falls back to CPU on JVM tests`() {
        assertEquals("cpu", InferenceProvider.resolve(""))
    }

    // ---- isNnapiAvailable() ----

    @Test
    fun `isNnapiAvailable is false in JVM test environment`() {
        // Build.VERSION.SDK_INT = 0 in JVM tests, which is < O_MR1 (27)
        assertFalse(InferenceProvider.isNnapiAvailable())
    }

    // ---- options ----

    @Test
    fun `options contains auto nnapi cpu`() {
        assertEquals(listOf("auto", "nnapi", "cpu"), InferenceProvider.options)
    }

    // ---- constants ----

    @Test
    fun `constants are stable`() {
        assertEquals("auto", InferenceProvider.AUTO)
        assertEquals("nnapi", InferenceProvider.NNAPI)
        assertEquals("cpu", InferenceProvider.CPU)
    }
}
