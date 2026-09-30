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
import org.junit.Assert.assertNull
import com.antivocale.app.testing.TempDataStoreRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the per-model preference migration (consolidation, catalog-driven layer):
 * a legacy key (`parakeet_model_path`, ...) is read as a fallback until the new
 * keyed preference (`sherpa_model_path_<entryId>`) is written, at which point the
 * legacy key is removed. A broken migration silently loses existing users'
 * selections and downloaded-model state, so every entry id is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesManagerMigrationTest {

    private val legacyKeys = mapOf(
        "sherpa-onnx" to "parakeet_model_path",
        "whisper" to "whisper_model_path",
        "qwen3-asr" to "qwen3_asr_model_path",
        "nemotron-streaming" to "nemotron_model_path",
        "gigaam" to "gigaam_model_path",
    )

    private fun newKeyName(entryId: String) = "sherpa_model_path_$entryId"


    @get:Rule
    val ds = TempDataStoreRule("prefs-migration", primeManager = false)
    private val context: Context get() = ds.context
    private val dataStore: DataStore<Preferences> get() = ds.dataStore

    @Test
    fun `legacy key read then new key written and legacy removed for each entry id`() = runTest {
        for ((entryId, legacyKeyName) in legacyKeys) {
            val legacyPath = "/legacy/$entryId"
            val newPath = "/new/$entryId"

            // Seed the legacy preference exactly as a pre-consolidation user would have it.
            dataStore.edit { it[stringPreferencesKey(legacyKeyName)] = legacyPath }

            // One shared DataStore instance: the manager must observe the same store we seed.
            val manager = PreferencesManagerImpl(context, dataStore).apply { initialize() }

            // 1. Legacy key read: the old value surfaces before any new key exists.
            assertEquals(legacyPath, manager.sherpaModelPath(entryId).first())

            // 2. Save under the new key: new key written, legacy key removed.
            manager.saveSherpaModelPath(entryId, newPath)
            val prefs = dataStore.data.first()
            assertEquals("new key must hold the saved path", newPath, prefs[stringPreferencesKey(newKeyName(entryId))])
            assertNull("legacy key must be removed after the new key is written", prefs[stringPreferencesKey(legacyKeyName)])

            // 3. Subsequent reads come from the new key.
            assertEquals(newPath, manager.sherpaModelPath(entryId).first())
        }
    }

    @Test
    fun `clearSherpaModelPath removes both the new and the legacy key`() = runTest {
        val (entryId, legacyKeyName) = legacyKeys.entries.first()
        dataStore.edit {
            it[stringPreferencesKey(newKeyName(entryId))] = "/new/$entryId"
            it[stringPreferencesKey(legacyKeyName)] = "/legacy/$entryId"
        }
        val manager = PreferencesManagerImpl(context, dataStore).apply { initialize() }

        manager.clearSherpaModelPath(entryId)

        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey(newKeyName(entryId))])
        assertNull(prefs[stringPreferencesKey(legacyKeyName)])
        assertNull(manager.sherpaModelPath(entryId).first())
    }
}