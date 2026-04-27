package com.antivocale.app.data

import org.junit.Assert.*
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun `DEFAULT_KEEP_ALIVE_TIMEOUT is 5 minutes`() {
        assertEquals(5, PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT)
    }

    @Test
    fun `DEFAULT_AUTO_COPY_ENABLED is false`() {
        assertFalse(PreferencesManager.DEFAULT_AUTO_COPY_ENABLED)
    }

    @Test
    fun `DEFAULT_VAD_ENABLED is false`() {
        assertFalse(PreferencesManager.DEFAULT_VAD_ENABLED)
    }

    @Test
    fun `DEFAULT_PROGRESSIVE_TRANSCRIPTION is true`() {
        assertTrue(PreferencesManager.DEFAULT_PROGRESSIVE_TRANSCRIPTION)
    }

    @Test
    fun `DEFAULT_TRANSCRIPTION_LANGUAGE is auto`() {
        assertEquals("auto", PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE)
    }

    @Test
    fun `DEFAULT_TRANSCRIPTION_BACKEND is not empty`() {
        assertTrue(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND.isNotBlank())
    }

    @Test
    fun `DEFAULT_SWIPE_ACTION_MODE is REVEAL`() {
        assertEquals("REVEAL", PreferencesManager.DEFAULT_SWIPE_ACTION_MODE)
    }

    @Test
    fun `DEFAULT_THREAD_COUNT is at least 2`() {
        assertTrue(PreferencesManager.DEFAULT_THREAD_COUNT >= 2)
    }

    @Test
    fun `DEFAULT_PROMPT_VALUE is not null`() {
        assertNotNull(PreferencesManager.DEFAULT_PROMPT_VALUE)
    }

    @Test
    fun `DEFAULT_THREAD_COUNT respects available processors`() {
        val expected = maxOf(2, Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(8)
        assertEquals(expected, PreferencesManager.DEFAULT_THREAD_COUNT)
    }

    @Test
    fun `DEFAULT_THREAD_COUNT is capped at 8`() {
        assertTrue(PreferencesManager.DEFAULT_THREAD_COUNT <= 8)
    }
}
