package com.antivocale.app.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * TASK-685: the Models-filter favorite preference round-trips through the
 * REAL DataStore file (same harness as PreferencesManagerDemotionTest). The
 * tri-state is the seed's contract and must survive a restart: key absent =
 * untouched (the onboarding seed may fire), "" = explicitly cleared (a
 * replayed tour must not re-seed), a code = the favorite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesManagerModelFilterTest {

    private val filterKey = stringPreferencesKey("model_filter_language")

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: PreferencesManagerImpl
    private lateinit var file: File
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("prefs-model-filter-${System.nanoTime()}", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = PreferencesManagerImpl(context, dataStore).apply { initialize() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `starts untouched with the key absent`() = runBlocking {
        assertNull(prefs.modelFilterLanguage.first())
        assertNull(dataStore.data.first()[filterKey])
    }

    @Test
    fun `a favorite round-trips and survives a restart`() = runBlocking {
        prefs.saveModelFilterLanguage("it")
        assertEquals("it", prefs.modelFilterLanguage.first())
        assertEquals("it", dataStore.data.first()[filterKey])

        // A fresh instance over the same store (the restart): initialize()
        // seeds the cache from disk, so the warm read is the saved favorite.
        val restarted = PreferencesManagerImpl(context, dataStore).apply { initialize() }
        assertEquals("it", restarted.modelFilterLanguage.first())
    }

    @Test
    fun `an explicit clear writes the empty value, not key absence`() = runBlocking {
        prefs.saveModelFilterLanguage("it")
        prefs.saveModelFilterLanguage("")
        // "" in the store, NOT a removed key: cleared is not untouched.
        assertEquals("", dataStore.data.first()[filterKey])
        assertEquals("", prefs.modelFilterLanguage.first())
    }
}
