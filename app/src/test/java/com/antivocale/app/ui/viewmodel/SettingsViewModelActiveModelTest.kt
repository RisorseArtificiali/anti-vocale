package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.ui.appearance.LauncherIconVariant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * [SettingsViewModel.uiState] through the REAL [ActiveModelRepository]
 * (TASK-258 acceptance #4).
 *
 * Construction follows the house LogsViewModel pattern: a
 * [StandardTestDispatcher] is installed as Main in [setup] and reset in
 * [tearDown]. [FakePreferencesManager] and [ActiveModelRepository] are real;
 * every other constructor dependency is a relaxed mockk. The Application
 * parameter is a relaxed mockk (SettingsViewModel is an AndroidViewModel).
 *
 * runCurrent() drains the StandardTestDispatcher's queue one step at a time
 * so the multi-hop flatMapLatest chain (backend flow -> per-backend path
 * flow -> uiState update) settles before assertions read emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelActiveModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        viewModel = SettingsViewModel(
            application = mockk<Application>(relaxed = true),
            preferencesManager = fakePrefs,
            logDao = mockk(relaxed = true),
            huggingFaceTokenManager = mockk(relaxed = true),
            huggingFaceAuthManager = mockk(relaxed = true),
            huggingFaceApiClient = mockk(relaxed = true),
            perAppPreferencesManager = mockk(relaxed = true),
            transcriptionCalibrator = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            shareShortcutManager = mockk(relaxed = true),
            // Enum returns are stubbed explicitly: a relaxed mock's enum answer
            // is version-dependent, and the ViewModel reads current() at init.
            launcherIconManager = mockk(relaxed = true) {
                every { current() } returns LauncherIconVariant.DEFAULT
            },
            // getString is stubbed so the fixed catalog display name (whisper_title)
            // resolves to a distinguishable value instead of a relaxed-mock empty string.
            activeModelRepository = ActiveModelRepository(
                fakePrefs,
                mockk<Context>(relaxed = true) {
                    every { getString(any()) } answers { "str:${args[0]}" }
                },
                staticRegistry(),
            ),
        )
    }

    @After
    fun tearDown() {
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

        // Switch to a second backend that has a DIFFERENT saved model path.
        fakePrefs._ggufModelPath.value = "/models/gemma-4-e2b-it.gguf"
        fakePrefs._transcriptionBackend.value = "gemma4_gguf"
        runCurrent()

        // Assertions (profile: model fields exactly; chosen over full-state
        // equality to stay robust against unrelated UiState churn).
        val state = viewModel.uiState.value
        assertEquals("gemma4_gguf", state.transcriptionBackend)
        assertEquals("/models/gemma-4-e2b-it.gguf", state.currentModelPath)
        assertEquals("gemma-4-e2b-it.gguf", state.currentModelName)
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
}
