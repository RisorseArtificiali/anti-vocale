package com.antivocale.app.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TASK-724, resolved by REFUTATION: the feared no-op in the TASK-643
 * legacy-URL cleanup cannot happen on the production path. AppModule
 * constructs the manager with initialize() (the cache is warm before any
 * consumer runs; SettingsViewModel's TASK-485 comment documents the same
 * fact), and toCached maps external_catalog_url, so the cleanup's
 * first() reads the warm cache emission carrying the persisted value.
 * What this test pins is the load-bearing chain the cleanup depends on:
 * a persisted legacy literal SURFACES through the flow's first read under
 * the production wiring, and the clear resets to the default. If someone
 * removes the toCached mapping (or the initialize() call), the cleanup
 * silently no-ops again and this fails here instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesManagerCatalogUrlTest {

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("prefs-catalog-url-${System.nanoTime()}", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `a persisted legacy literal surfaces through the flow under production wiring`() = runTest {
        val legacy = ExternalCatalogRepository.LEGACY_DEFAULT_CATALOG_URL
        dataStore.edit { it[stringPreferencesKey("external_catalog_url")] = legacy }

        // The production wiring: AppModule applies initialize(), which
        // primes the cache from DataStore before the reference escapes.
        val manager = PreferencesManagerImpl(context, dataStore).apply { initialize() }

        // The cleanup at BridgeApplication compares this read against the
        // legacy literal: it must see the persisted value, not the default.
        assertEquals(legacy, manager.externalCatalogUrl.first())

        // The cleanup's clear removes the key, so the next launch reads the
        // default and the comparison never matches again.
        manager.clearExternalCatalogUrl()
        assertEquals(
            PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL,
            manager.externalCatalogUrl.first(),
        )
    }

    @Test
    fun `the 684 key surfaces through the flow now that toCached maps it`() = runTest {
        // The original TASK-684 bug: the key was missing from toCached, so
        // the warm cache carried the default and the flow's onStart emission
        // masked a persisted opt-out. Pin the restored mapping.
        dataStore.edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("interrupted_run_notifications")] = false
        }
        val manager = PreferencesManagerImpl(context, dataStore).apply { initialize() }
        assertEquals(false, manager.interruptedRunNotifications.first())
    }
}
