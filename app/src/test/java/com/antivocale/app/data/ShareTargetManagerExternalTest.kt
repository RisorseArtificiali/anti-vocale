package com.antivocale.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.emptyRecordsProvider
import com.antivocale.app.transcription.seedCatalogForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Family-level sync for the ShareExternal alias (plan v2a, Task 10), against Robolectric's
 * real PackageManager: the component state is read back via getComponentEnabledSetting,
 * no mocking of the package layer. Enabled iff advanced sharing AND a valid record;
 * deleting the last record disables it; the blank-alias skip writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ShareTargetManagerExternalTest {

    private lateinit var context: Context
    private lateinit var fake: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var registry: BackendRegistry
    private lateinit var manager: ShareTargetManager

    private lateinit var familyAlias: ComponentName

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        familyAlias = ComponentName(context, "com.antivocale.app.ShareExternal")
        fake = FakePreferencesManager()
        store = ExternalModelStore(fake)
        seedCatalogForTest()
        registry = BackendRegistry(store, emptyRecordsProvider())
    }

    // TASK-738: the manager launches internally; an unconfined test
    // dispatcher on both the scope and the internal launch runs each
    // fire-and-forget body inline inside runTest (the scheduler comes from
    // the TestScope, so the manager is built per test).
    private fun newManager(scheduler: TestCoroutineScheduler): ShareTargetManager {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        // SupervisorJob mirrors the production @ApplicationScope semantics:
        // one failing sync must not kill the scope for the later fire-and-forgets.
        return ShareTargetManager(
            context, fake, registry, store,
            CoroutineScope(SupervisorJob() + dispatcher), dispatcher,
        )
    }

    private suspend fun addRecord(id: String = "abc123def456", dirPresent: Boolean = true) {
        val dir: File = if (dirPresent) Files.createTempDirectory("external-record").toFile() else File("/gone")
        store.add(
            ExternalModelRecord(
                id = id,
                displayName = "GigaAM v3",
                dir = dir.absolutePath,
                family = ModelFamily.TRANSDUCER,
                modelType = "nemo_transducer",
                languages = listOf("ru"),
                source = ExternalModelSource.LOCAL,
                sourceUrl = null,
                files = emptyMap(),
                sizeBytes = 1L,
                importedAt = 0L,
            )
        )
    }

    private fun familyEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(familyAlias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Test
    fun `advanced sharing on with one valid record enables the family alias`() = runTest {
        manager = newManager(testScheduler)
        fake._advancedSharingEnabled.value = true
        addRecord(dirPresent = true)

        manager.syncAll()
        advanceUntilIdle()

        assertTrue("family alias must be enabled", familyEnabled())
    }

    @Test
    fun `advanced sharing off disables the family alias even with records`() = runTest {
        manager = newManager(testScheduler)
        fake._advancedSharingEnabled.value = true
        addRecord(dirPresent = true)
        manager.syncAll()
        advanceUntilIdle()
        assertTrue(familyEnabled())

        fake._advancedSharingEnabled.value = false
        manager.setAdvancedSharingEnabled(false)
        advanceUntilIdle()

        assertFalse(familyEnabled())
    }

    @Test
    fun `zero valid records disables the family alias`() = runTest {
        manager = newManager(testScheduler)
        fake._advancedSharingEnabled.value = true
        addRecord(dirPresent = false)   // directory gone: not a valid record

        manager.syncAll()
        advanceUntilIdle()

        assertFalse(familyEnabled())
    }

    @Test
    fun `deleting the last external record disables the family alias`() = runTest {
        manager = newManager(testScheduler)
        fake._advancedSharingEnabled.value = true
        addRecord(dirPresent = true)
        manager.syncAll()
        advanceUntilIdle()
        assertTrue(familyEnabled())

        // The real flow deletes from the store BEFORE notifying the manager.
        store.delete("abc123def456")
        manager.onModelDeleted("external:abc123def456")
        advanceUntilIdle()

        assertFalse(familyEnabled())
    }

    @Test
    fun `no blank-className component is ever written`() = runTest {
        manager = newManager(testScheduler)
        fake._advancedSharingEnabled.value = true
        addRecord(dirPresent = true)

        manager.syncAll()
        advanceUntilIdle()

        val blank = ComponentName(context, "")
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(blank)
        )
    }
}
