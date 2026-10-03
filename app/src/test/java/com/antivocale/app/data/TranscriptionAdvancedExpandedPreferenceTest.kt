package com.antivocale.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-588.1 (review F4): the advanced-reveal preference round-trips
 * through the fake the same way the impl does through DataStore; the
 * default is COLLAPSED.
 */
class TranscriptionAdvancedExpandedPreferenceTest {

    @Test
    fun `defaults collapsed and round-trips through the fake`() = runTest {
        val fake = FakePreferencesManager()
        assertFalse(fake.settingsTranscriptionAdvancedExpanded.first())
        fake.saveSettingsTranscriptionAdvancedExpanded(true)
        assertTrue(fake.settingsTranscriptionAdvancedExpanded.first())
        fake.saveSettingsTranscriptionAdvancedExpanded(false)
        assertFalse(fake.settingsTranscriptionAdvancedExpanded.first())
    }
}
