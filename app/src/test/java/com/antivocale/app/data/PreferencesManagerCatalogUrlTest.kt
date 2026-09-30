package com.antivocale.app.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.antivocale.app.testing.TempDataStoreRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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


    @get:Rule
    val ds = TempDataStoreRule("prefs-catalog-url", primeManager = false)
    private val context: Context get() = ds.context
    private val dataStore: DataStore<Preferences> get() = ds.dataStore

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
