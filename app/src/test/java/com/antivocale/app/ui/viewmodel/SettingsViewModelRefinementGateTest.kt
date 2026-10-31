package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.SherpaModelManager
import io.mockk.coEvery
import io.mockk.mockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-932 (GH #176): the two-pass toggle's availability gate. ANY two
 * installed models pair (the streaming entry is no longer required); the
 * toggle unlocks only when a fast arm resolves AND it differs from the
 * selected backend. The resolution itself is the shared owner's
 * (SherpaModelManager.installedFastEntryId, covered by
 * ModelManagerCatalogTest); here it is stubbed so the gate's own logic is
 * isolated.
 *
 * The gate combine launches on the real Dispatchers.Default (the
 * TASK-603 F6 block it replaced launched there too), so the settled state
 * is polled with the bounded busy-wait the LitertLmImport suite
 * established. Every predicate names the state that proves the combine
 * actually ran (the gate's initial value is the same false/null a locked
 * gate reports).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelRefinementGateTest {

    private val testDispatcher = StandardTestDispatcher()
    private val externalRecords = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        harness = SettingsViewModelTestHelper(externalRecords = externalRecords)
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    @After
    fun tearDown() {
        harness.cleanup()
        Dispatchers.resetMain()
    }

    /** Installs two built-in models through the saved-path preference. */
    private fun installTwoBackends() {
        fakePrefs._sherpaModelPath("sherpa-onnx").value = "/models/parakeet"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
    }

    /**
     * Polls until [settled] holds (5s cap, then the caller's assertion
     * names the failure): the combine hops to the real Default dispatcher,
     * which the virtual-time scheduler cannot drain.
     */
    private fun TestScope.awaitGate(settled: () -> Boolean) {
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000
        while (!settled() && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(20)
        }
    }

    @Test
    fun `two installed backends with a distinct fast arm unlock the toggle`() = runTest {
        mockkObject(SherpaModelManager.Companion) {
            coEvery { SherpaModelManager.installedFastEntryId(any()) } returns "whisper"
            installTwoBackends()
            fakePrefs._transcriptionBackend.value = "sherpa-onnx"
            awaitGate { viewModel.fastBackendId.value == "whisper" }
            assertTrue(viewModel.refinementAvailable.value)
            assertEquals("whisper", viewModel.fastBackendId.value)
        }
    }

    @Test
    fun `a single installed backend keeps the toggle locked`() = runTest {
        mockkObject(SherpaModelManager.Companion) {
            coEvery { SherpaModelManager.installedFastEntryId(any()) } returns "whisper"
            fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
            fakePrefs._transcriptionBackend.value = "whisper"
            awaitGate { viewModel.fastBackendId.value == "whisper" }
            assertFalse(viewModel.refinementAvailable.value)
        }
    }

    @Test
    fun `a fast arm equal to the selected backend keeps the toggle locked`() = runTest {
        mockkObject(SherpaModelManager.Companion) {
            coEvery { SherpaModelManager.installedFastEntryId(any()) } returns "sherpa-onnx"
            installTwoBackends()
            fakePrefs._transcriptionBackend.value = "sherpa-onnx"
            awaitGate { viewModel.fastBackendId.value == "sherpa-onnx" }
            assertFalse(viewModel.refinementAvailable.value)
        }
    }

    @Test
    fun `losing the fast arm re-locks the toggle and publishes null`() = runTest {
        mockkObject(SherpaModelManager.Companion) {
            coEvery { SherpaModelManager.installedFastEntryId(any()) } returns "whisper"
            installTwoBackends()
            fakePrefs._transcriptionBackend.value = "sherpa-onnx"
            awaitGate { viewModel.refinementAvailable.value }
            assertTrue(viewModel.refinementAvailable.value)

            // The uninstall shape: the resolution goes away, a backend
            // switch forces the next combine emission, and the gate locks.
            coEvery { SherpaModelManager.installedFastEntryId(any()) } returns null
            fakePrefs._transcriptionBackend.value = "qwen3-asr"
            awaitGate { viewModel.fastBackendId.value == null }
            assertFalse(viewModel.refinementAvailable.value)
            assertNull(viewModel.fastBackendId.value)
        }
    }
}
