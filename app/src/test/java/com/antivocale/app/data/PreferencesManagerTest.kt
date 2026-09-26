package com.antivocale.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.first

class PreferencesManagerTest {

    @Test
    fun `DEFAULT_KEEP_ALIVE_TIMEOUT is 5 minutes`() {
        assertEquals(5, PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT)
    }

    // TASK-681: the LAN-offload gate is opt-in and its config starts empty;
    // the model default comes from the backend constant so the two can never
    // drift.
    @Test
    fun `DEFAULT_REMOTE_OMNIVOICE_ENABLED is false`() {
        assertFalse(PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENABLED)
    }

    @Test
    fun `DEFAULT_REMOTE_OMNIVOICE endpoint and key start blank`() {
        assertEquals("", PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_ENDPOINT)
        assertEquals("", PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_API_KEY)
    }

    @Test
    fun `the fake round-trips the LAN-offload config`() = kotlinx.coroutines.test.runTest {
        val fake = FakePreferencesManager()
        assertFalse(fake.remoteOmnivoiceEnabled.first())
        assertEquals(PreferencesManager.DEFAULT_REMOTE_OMNIVOICE_MODEL,
            fake.remoteOmnivoiceModel.first())
        fake.saveRemoteOmnivoiceEnabled(true)
        fake.saveRemoteOmnivoiceEndpoint("  http://192.168.1.10:3900 ")
        fake.saveRemoteOmnivoiceApiKey(" k ")
        fake.saveRemoteOmnivoiceModel(" my-engine ")
        assertTrue(fake.remoteOmnivoiceEnabled.first())
        assertEquals("http://192.168.1.10:3900", fake.remoteOmnivoiceEndpoint.first())
        assertEquals("k", fake.remoteOmnivoiceApiKey.first())
        assertEquals("my-engine", fake.remoteOmnivoiceModel.first())
        // The combined write mirrors one Save press: all three, trimmed.
        fake.saveRemoteOmnivoiceConfig(" http://mac.lan:3900 ", "k2", " m2 ")
        assertEquals("http://mac.lan:3900", fake.remoteOmnivoiceEndpoint.first())
        assertEquals("k2", fake.remoteOmnivoiceApiKey.first())
        assertEquals("m2", fake.remoteOmnivoiceModel.first())
    }

    @Test
    fun `disabling the LAN-offload gate resets a selection pointing at it`() = kotlinx.coroutines.test.runTest {
        val fake = FakePreferencesManager()
        fake.saveTranscriptionBackend(com.antivocale.app.transcription.RemoteOmnivoiceBackend.BACKEND_ID)
        fake.saveRemoteOmnivoiceEnabled(false)
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, fake.transcriptionBackend.first())
        // The reset is specific: any other selection survives a disable.
        fake.saveTranscriptionBackend("whisper")
        fake.saveRemoteOmnivoiceEnabled(true)
        fake.saveRemoteOmnivoiceEnabled(false)
        assertEquals("whisper", fake.transcriptionBackend.first())
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
    fun `DEFAULT_TRANSCRIPTION_LANGUAGE is the system sentinel`() {
        // TASK-434: the untouched default follows the app locale (never "auto",
        // which is the explicit model-side auto-detection choice).
        assertEquals("system", PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE)
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
    fun `DEFAULT_SHOW_TECHNICAL_DETAILS is false`() {
        // TASK-616: the technical line is diagnostic detail, hidden unless asked for.
        assertFalse(PreferencesManager.DEFAULT_SHOW_TECHNICAL_DETAILS)
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
