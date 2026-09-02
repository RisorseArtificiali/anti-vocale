package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.transcription.SherpaBackend
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retranscribe-dialog display-name regression (the "missed one dispatch site"
 * bug class): the picker previously forwarded each backend's raw `displayName`
 * (SherpaBackend returns the catalog entry id, e.g. "sherpa-onnx",
 * "nemotron-streaming"). The dialog MUST derive its labels through the registry
 * descriptor display-name contract (fixed localized resource, else path-derived
 * variant title), so users see "Parakeet TDT" / "Nemotron 3.5" and never a raw id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogsViewModelRetranscribeNamesTest {

    private lateinit var manager: TranscriptionBackendManager
    private lateinit var preferences: PreferencesManager
    private lateinit var viewModel: LogsViewModel
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        manager = mockk(relaxed = true)
        preferences = stubPreferencesManager()
        every { preferences.sherpaModelPath(any()) } returns flowOf("/data/models/saved")
        viewModel = LogsViewModel(manager, mockk<LogDao>(relaxed = true), preferences, staticRegistry(), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `retranscribe dialog shows localized catalog names not raw backend ids`() = runTest {
        every { manager.activeBackendId } returns MutableStateFlow("sherpa-onnx")
        every { manager.getAvailableBackends() } returns listOf(
            SherpaBackend("sherpa-onnx"),
            SherpaBackend("nemotron-streaming"),
            SherpaBackend("whisper"),
            SherpaBackend("qwen3-asr"),
            SherpaBackend("gigaam"),
        )

        val options = viewModel.getAvailableAudioBackendsWithModels(context)
        val byId = options.associateBy { it.backendId }

        assertEquals(5, options.size)
        assertEquals("Parakeet TDT", byId["sherpa-onnx"]?.displayName)
        assertEquals("Nemotron 3.5", byId["nemotron-streaming"]?.displayName)
        assertEquals("Whisper", byId["whisper"]?.displayName)
        assertEquals("Qwen3-ASR (52 languages)", byId["qwen3-asr"]?.displayName)
        assertEquals("GigaAM v3", byId["gigaam"]?.displayName)
    }

    @Test
    fun `dialog labels are the localized names not the entry ids`() = runTest {
        every { manager.activeBackendId } returns MutableStateFlow("sherpa-onnx")
        every { manager.getAvailableBackends() } returns listOf(SherpaBackend("sherpa-onnx"))

        val options = viewModel.getAvailableAudioBackendsWithModels(context)

        assertEquals(listOf("sherpa-onnx") , options.map { it.backendId })
        assertEquals(listOf("Parakeet TDT"), options.map { it.displayName })
    }

    @Test
    fun `gemma entry shows its fixed localized name not a path-derived label`() = runTest {
        // The LLM descriptor is the only static one without a catalog display:
        // parity with main requires the fixed "Gemma (LiteRT-LM)" label, not the
        // path-derived fallback (review nit 2, PR #28).
        every { manager.activeBackendId } returns MutableStateFlow("llm")
        every { preferences.modelPath } returns flowOf("/data/models/gemma-4-e2b")
        every { manager.getAvailableBackends() } returns listOf(mockk<com.antivocale.app.transcription.LlmTranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { supportsAudio } returns true
            every { displayName } returns "llm"
        })

        val options = viewModel.getAvailableAudioBackendsWithModels(context)

        assertEquals(listOf("Gemma (LiteRT-LM)"), options.map { it.displayName })
    }
}