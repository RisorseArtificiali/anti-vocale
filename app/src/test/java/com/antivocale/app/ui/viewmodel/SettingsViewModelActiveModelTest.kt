package com.antivocale.app.ui.viewmodel

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelFamily
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests proving that a preference change propagates reactively into
 * [SettingsViewModel.uiState] through the REAL
 * [com.antivocale.app.data.ActiveModelRepository]
 * (TASK-258 acceptance #4).
 *
 * Construction goes through [SettingsViewModelTestHelper] (TASK-848): a
 * [StandardTestDispatcher] is installed as Main in [setup] and reset in
 * [tearDown], with this suite's seams (the class-level records flow and
 * the getString-stubbed repository context) passed to the helper.
 *
 * runCurrent() drains the StandardTestDispatcher's queue one step at a time
 * so the multi-hop flatMapLatest chain (backend flow -> per-backend path
 * flow -> uiState update) settles before assertions read emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelActiveModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val externalRecords = MutableStateFlow<List<ExternalModelRecord>>(emptyList())
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // The shared construction harness (TASK-848); this suite's seam:
        // the class-level mutable records flow, plus the getString-stubbed
        // repository context (fixed catalog display names resolve
        // distinguishably instead of a relaxed-mock empty string).
        harness = SettingsViewModelTestHelper(
            externalRecords = externalRecords,
            repositoryContext = mockk<Context>(relaxed = true) {
                every { getString(any()) } answers { "str:${args[0]}" }
            },
        )
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    @After
    fun tearDown() {
        harness.cleanup()
        Dispatchers.resetMain()
    }

    /**
     * Mechanical wiring proof: drive the fake preferences to a known state
     * (whisper backend with a saved whisper model path), start the collector
     * via loadCurrentModel(), drain the dispatcher, and assert the UiState
     * mirrors the ActiveModel emission.
     *
     * The path does not point at a real whisper model directory. Whisper has a
     * fixed catalog display name (whisper_title), so the repository's name
     * derivation is the localized title, not the last path segment.
     */
    @Test
    fun `loadCurrentModel mirrors whisper backend and saved model path in uiState`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper-test"

        viewModel.loadCurrentModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("whisper", state.transcriptionBackend)
        assertEquals("/models/whisper-test", state.currentModelPath)
        assertEquals("str:${R.string.whisper_title}", state.currentModelName)
    }

    @Test
    fun `backend switch mid-collection updates state reactively`() = runTest {
        // Collector running on the initial backend with a saved model path.
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/models/whisper-initial"
        viewModel.loadCurrentModel()
        runCurrent()

        // Switch to a second backend that has a DIFFERENT saved model path
        // (deliberately unregistered: the generic modelPath preference serves
        // it and the name derives from the file name).
        fakePrefs._modelPath.value = "/models/gemma-4-e2b-it.taskml"
        fakePrefs._transcriptionBackend.value = "no-such-backend"
        runCurrent()

        // Assertions (profile: model fields exactly; chosen over full-state
        // equality to stay robust against unrelated UiState churn).
        val state = viewModel.uiState.value
        assertEquals("no-such-backend", state.transcriptionBackend)
        assertEquals("/models/gemma-4-e2b-it.taskml", state.currentModelPath)
        assertEquals("gemma-4-e2b-it.taskml", state.currentModelName)
    }

    /**
     * TASK-458: the Transcription Language picker derives from the ACTIVE
     * backend through the same ActiveModelRepository chain (the bundled
     * catalog is already seeded by staticRegistry() in setup): the Whisper
     * Distil-IT directory name yields its single-language set, and a backend
     * without language conditioning (Parakeet, the default) disables the
     * picker.
     */
    @Test
    fun `transcription language picker derives from the active backend and model`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/models/sherpa-onnx-whisper-distil-large-v3-it"

        val collector = launch { viewModel.transcriptionLanguagePicker.collect {} }
        runCurrent()

        val whisper = viewModel.transcriptionLanguagePicker.value
        assertTrue(whisper.conditioningAvailable)
        assertEquals(setOf("it"), whisper.offeredCodes)

        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        runCurrent()

        val parakeet = viewModel.transcriptionLanguagePicker.value
        assertFalse(parakeet.conditioningAvailable)
        assertTrue(parakeet.offeredCodes.isEmpty())

        collector.cancel()
    }

    /**
     * TASK-611: the language chip needs a model that detects the language.
     * The family enum decides, NOT modelType: whisper and sense_voice records
     * carry a blank modelType by design, so a stringly check never matches.
     */
    @Test
    fun `language chip availability follows the backend family`() = runTest {
        val collector = launch { viewModel.languageChipAvailable.collect {} }
        runCurrent()
        assertFalse("parakeet, the default backend, cannot detect", viewModel.languageChipAvailable.value)

        fakePrefs._transcriptionBackend.value = "whisper"
        runCurrent()
        assertTrue("built-in whisper can detect", viewModel.languageChipAvailable.value)

        fakePrefs._transcriptionBackend.value = "external:testid"
        runCurrent()
        assertFalse("an external transducer cannot detect", viewModel.languageChipAvailable.value)

        externalRecords.value = listOf(
            sampleRecord().copy(family = ModelFamily.WHISPER, modelType = ""),
        )
        runCurrent()
        assertTrue("an external whisper can detect, blank modelType included", viewModel.languageChipAvailable.value)

        externalRecords.value = listOf(
            sampleRecord().copy(family = ModelFamily.SENSE_VOICE, modelType = ""),
        )
        runCurrent()
        assertTrue("an external sense_voice can detect", viewModel.languageChipAvailable.value)

        collector.cancel()
    }
}
