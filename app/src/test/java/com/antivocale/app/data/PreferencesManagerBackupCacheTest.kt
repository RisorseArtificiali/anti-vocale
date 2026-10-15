package com.antivocale.app.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.antivocale.app.testing.TempDataStoreRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-847 item 5 (code-review F1 of the backup delta): a bulk import must
 * keep the in-memory cache in step with the store. Cache-seeded flows (the
 * .onStart pattern) make a one-shot .first() consumer read the CACHE, not
 * the store: an importPortableSettings that edited DataStore without
 * re-priming left imported values invisible to benchmark config, catalog
 * fetches, and retranscribe until process restart (some flows read the
 * store directly and would have seen the import; the cache-seeded ones
 * would not). The export side's whitelist filter is pinned here too: it is
 * the ONLY behavioral gate keeping the bearer secret out of every archive
 * (BackupSettingsKeysTest checks key classification statically; the codec
 * tests disclaim the whitelist). Same harness as the migration/demotion
 * tests (the REAL Impl over a temp store).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesManagerBackupCacheTest {

    @get:Rule
    val store = TempDataStoreRule("backup-cache")

    @Test
    fun `an import re-primes the WHOLE cache, not just the payload's fields`() = runTest {
        assertFalse(store.prefs.autoCopyEnabled.first())
        // A second cache-seeded field of a different kind: a per-key
        // re-prime "optimization" would leave this one stale until restart.
        // 6 is a legal count that no sane device default pins, and the
        // pre-assert closes even that hole.
        val threadsBefore = store.prefs.threadCount.first()
        assertFalse(threadsBefore == 6)

        store.prefs.importPortableSettings(
            mapOf("auto_copy_enabled" to true, "thread_count" to 6))

        // Both .first() reads ride the cache seed: the full re-prime inside
        // the import is what makes these pass WITHOUT a new manager.
        assertTrue(store.prefs.autoCopyEnabled.first())
        assertEquals(6, store.prefs.threadCount.first())
    }

    @Test
    fun `a hostile payload neither writes non-portable names nor wipes the cache`() = runTest {
        // A stored non-default survivor proves the re-prime takes the store
        // snapshot, not a defaults reset (a wiped cache would read the
        // theme default until restart).
        store.prefs.saveThemeMode("DARK")

        store.prefs.importPortableSettings(
            mapOf("auto_copy_enabled" to true, "remote_omnivoice_api_key" to "secret"))

        // The ONLY names in the store are portable ones (the survivor's
        // theme_mode included): no literal key to drift behind a
        // production rename (an assertNull on the old name would pass
        // vacuously).
        val names = store.dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(setOf("theme_mode", "auto_copy_enabled"), names)
        assertTrue(names.all { BackupSettingsKeys.isPortable(it) })
        // The cache is re-primed WHOLE from the store: the stored theme
        // merely lands back on its own value.
        assertEquals("DARK", store.prefs.themeMode.first())
    }

    @Test
    fun `the export filter never emits a non-portable name`() = runTest {
        store.prefs.saveAutoCopyEnabled(true)
        // The secret placed in the store the way a compromised or restored
        // file would leave it.
        store.dataStore.edit {
            it[stringPreferencesKey("remote_omnivoice_api_key")] = "secret"
            it[intPreferencesKey("thread_count")] = 6
        }

        val exported = store.prefs.exportPortableSettings()

        assertEquals(mapOf<String, Any>("auto_copy_enabled" to true, "thread_count" to 6),
            exported)
    }
}
