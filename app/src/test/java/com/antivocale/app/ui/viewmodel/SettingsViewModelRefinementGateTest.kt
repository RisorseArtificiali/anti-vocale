package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.SherpaModelManager
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
 * toggle unlocks when a fast arm resolves, which by the probe's exclusion
 * of the accurate side (the pin, else the selected backend) is always a
 * second model besides the refinement target. The resolution itself is the
 * shared owner's (SherpaModelManager.installedFastEntryId, covered by
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
        // TASK-945: the mock lives for the whole class, not per test body.
        // The ViewModel's combine coroutines hop to the real Default
        // dispatcher, so a refresh can still be in flight when a test
        // returns; unmocking inside the body let that late call hit the
        // real installedFastEntryId on fake /models paths, and the uncaught
        // exception was attributed to the NEXT test (the shifting
        // UncaughtExceptionsBeforeTest victims on CI).
        mockkObject(SherpaModelManager.Companion)
        harness = SettingsViewModelTestHelper(externalRecords = externalRecords)
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    @After
    fun tearDown() {
        // TASK-960: guarded so a @Before that died mid-harness-construction
        // still reaches resetMain (an unguarded lateinit access here would
        // leave Main swapped for the NEXT class).
        if (::harness.isInitialized) harness.cleanup()
        Dispatchers.resetMain()
        unmockkAll()
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
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGate { viewModel.fastBackendId.value == "whisper" }
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("whisper", viewModel.fastBackendId.value)
    }

    @Test
    fun `a single installed backend keeps the toggle locked`() = runTest {
        // The only install IS the accurate side, so the exclusion-aware
        // probe finds nothing left and answers null (the real manager's
        // behavior for this shape).
        coEvery { SherpaModelManager.installedFastEntryId(any(), "whisper") } returns null
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
        fakePrefs._transcriptionBackend.value = "whisper"
        awaitGate { viewModel.refinementAvailable.value == false &&
            viewModel.fastBackendId.value == null }
        assertFalse(viewModel.refinementAvailable.value)
    }

    @Test
    fun `gh 176 - the selected backend is excluded from the fast arm so two installed models unlock the toggle`() = runTest {
        // The probe answers ONLY the exclusion of the selected backend:
        // the gate must pass the accurate side, and with parakeet
        // selected plus whisper installed the arm is whisper (the
        // pre-fix gate compared fast != selected instead and locked).
        coEvery {
            SherpaModelManager.installedFastEntryId(any(), "sherpa-onnx")
        } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGate { viewModel.fastBackendId.value == "whisper" }
        assertTrue(viewModel.refinementAvailable.value)
    }

    @Test
    fun `streaming selected still unlocks and the fast arm resolves to the smallest other install`() = runTest {
        coEvery {
            SherpaModelManager.installedFastEntryId(any(), "nemotron-streaming")
        } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "nemotron-streaming"
        awaitGate { viewModel.fastBackendId.value == "whisper" }
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("whisper", viewModel.fastBackendId.value)
    }

    @Test
    fun `pinning the only other model keeps the gate consistent with the runtime`() = runTest {
        // The pin becomes the accurate side, so the exclusion is the
        // PIN (not the selection): the probe excludes whisper and the
        // smallest other install, parakeet, is the fast arm.
        coEvery { SherpaModelManager.installedFastEntryId(any(), "whisper") } returns
            "sherpa-onnx"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        fakePrefs._refinementModelBackendId.value = "whisper"
        awaitGate { viewModel.fastBackendId.value == "sherpa-onnx" }
        assertTrue(viewModel.refinementAvailable.value)
        assertEquals("sherpa-onnx", viewModel.fastBackendId.value)
    }

    @Test
    fun `a stale pin keeps the toggle locked even when a fast arm resolves`() = runTest {
        // The pin names a model that was deleted after pinning: the probe
        // still finds a fast arm besides it, but the accurate side itself
        // is unusable and every run would degrade to SKIP_REFINE_LOAD_FAILED,
        // so the gate locks on the pin, not on the arm.
        coEvery { SherpaModelManager.installedFastEntryId(any(), "gigaam") } returns
            "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper"
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._refinementModelBackendId.value = "gigaam"
        awaitGate { viewModel.fastBackendId.value == "whisper" }
        assertEquals("whisper", viewModel.fastBackendId.value)
        assertFalse(viewModel.refinementAvailable.value)
    }

    @Test
    fun `losing the fast arm re-locks the toggle and publishes null`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns "whisper"
        installTwoBackends()
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        awaitGate { viewModel.refinementAvailable.value }
        assertTrue(viewModel.refinementAvailable.value)

        // The uninstall shape: the resolution goes away, a backend
        // switch forces the next combine emission, and the gate locks.
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
        fakePrefs._transcriptionBackend.value = "qwen3-asr"
        awaitGate { viewModel.fastBackendId.value == null }
        assertFalse(viewModel.refinementAvailable.value)
        assertNull(viewModel.fastBackendId.value)
    }
}
