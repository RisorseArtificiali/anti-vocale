package com.antivocale.app.transcription

import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.DefaultExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.catalog.BundledCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-640: quarantined external records must not surface as selectable
 * backends; unquarantined ones must. Wired through the PRODUCTION provider
 * (DefaultExternalModelRecordsProvider over the store's validRecordsFlow), so
 * the test pins the real loadability definition rather than a hand-made flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackendRegistryQuarantineTest {

    @Before
    fun setUp() {
        BundledCatalog.attach(ApplicationProvider.getApplicationContext())
    }

    private fun record(id: String, quarantined: Boolean) = ExternalModelRecord(
        id = id, displayName = "model-$id", dir = "/tmp/does-not-matter-$id",
        family = ModelFamily.CTC, modelType = "nemo_ctc", languages = emptyList(),
        source = ExternalModelSource.URL, sourceUrl = null,
        files = emptyMap(), sizeBytes = 1, importedAt = 0, quarantined = quarantined,
    )

    @Test
    fun `quarantined records are excluded from the backends list`() = runTest {
        val live = record("live", quarantined = false)
        val dead = record("dead", quarantined = true)
        val store = ExternalModelStore(FakePreferencesManager(), dirExists = { true })
        store.add(live)
        store.add(dead)
        val provider = DefaultExternalModelRecordsProvider(
            store,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        // The provider collects the store's cold flow into a StateFlow: wait
        // for the first emission before asserting on the registry's snapshot.
        kotlinx.coroutines.withTimeout(1_000) {
            while (provider.records.value.isEmpty()) kotlinx.coroutines.yield()
        }
        val registry = BackendRegistry(store, provider)
        val ids = registry.backends.map { it.backendId }
        assertTrue(live.backendId in ids)
        assertFalse(dead.backendId in ids)
    }
}
