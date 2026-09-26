package com.antivocale.app.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-675: the demoted-backends preference round-trips through the REAL
 * DataStore file (same harness as PreferencesManagerMigrationTest): mark
 * lands in the store, is idempotent, and clearing the last entry removes
 * the key entirely so a cleared state reads like a fresh install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesManagerDemotionTest {

    private val demotedKey = stringSetPreferencesKey("demoted_backends")

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: PreferencesManagerImpl
    private lateinit var file: File
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("prefs-demoted-${System.nanoTime()}", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = PreferencesManagerImpl(context, dataStore).apply { initialize() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `starts empty`() = runBlocking {
        assertEquals(emptySet<String>(), prefs.demotedBackends.first())
        assertNull(dataStore.data.first()[demotedKey])
    }

    @Test
    fun `mark round-trips into the store and is idempotent`() = runBlocking {
        prefs.markBackendDemoted("whisper")
        prefs.markBackendDemoted("external:abc")
        assertEquals(setOf("whisper", "external:abc"), prefs.demotedBackends.first())
        assertEquals(setOf("whisper", "external:abc"), dataStore.data.first()[demotedKey])

        prefs.markBackendDemoted("whisper")
        assertEquals(setOf("whisper", "external:abc"), prefs.demotedBackends.first())
    }

    @Test
    fun `clear removes one entry and the last clear removes the key`() = runBlocking {
        prefs.markBackendDemoted("whisper")
        prefs.markBackendDemoted("external:abc")

        prefs.clearDemotedBackend("whisper")
        assertEquals(setOf("external:abc"), prefs.demotedBackends.first())

        prefs.clearDemotedBackend("external:abc")
        assertEquals(emptySet<String>(), prefs.demotedBackends.first())
        assertNull(dataStore.data.first()[demotedKey])

        // Clearing an absent id stays a no-op.
        prefs.clearDemotedBackend("whisper")
        assertEquals(emptySet<String>(), prefs.demotedBackends.first())
    }
}
