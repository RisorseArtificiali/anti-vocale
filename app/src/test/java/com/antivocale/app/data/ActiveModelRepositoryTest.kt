package com.antivocale.app.data

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * TDD red-phase test for [ActiveModelRepository].
 *
 * Contract pinned by this test:
 *   data class ActiveModel(val backendId: String, val modelPath: String?, val modelName: String?)
 *   class ActiveModelRepository @Inject constructor(
 *       preferencesManager: PreferencesManager,
 *       @ApplicationContext context: Context,
 *   ) {
 *       val activeModelFlow: Flow<ActiveModel>
 *   }
 *
 * The flow MUST be reactive: derived via transcriptionBackend.flatMapLatest so that
 * EITHER a backend change OR a per-backend model-path change emits a new ActiveModel.
 *
 * Name assertion strategy:
 *   modelName is asserted for backends whose name is derived from the file path
 *   (llm / gguf backends: filename without extension). These don't need Android resources.
 *   For other backends (sherpa-onnx, whisper, qwen3-asr, nemotron) the name comes from
 *   Context.getString, which requires Robolectric or an instrumented test. We only assert
 *   that modelName is not blank for those, keeping this test resource-independent.
 *
 * This test uses the shared [FakePreferencesManager] with MutableStateFlow fields so that
 * flows can be driven mid-test (emit new values and verify the repository reacts).
 *
 * NOTE: runCurrent() calls are required after backgroundScope.launch and after
 * MutableStateFlow mutations so the StandardTestDispatcher drains the multi-hop
 * flatMapLatest chain (backend flow -> per-backend path flow) before assertions
 * read emissions. A single yield() is insufficient because flatMapLatest chains
 * emissions across coroutine resumptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveModelRepositoryTest {

    // -- Shared test setup --
    // FakePreferencesManager lives in its own file (shared with the ViewModel tests).

    private val fakePrefs = FakePreferencesManager()

    /**
     * A relaxed mockk Context stands in for @ApplicationContext in unit tests.
     * Resource-backed name derivation (parakeet/nemotron/whisper-variant) is
     * never asserted exactly here, so the mock returning defaults for getString
     * is fine. The backends we assert names on (gemma4_gguf, llm) derive the
     * name from the file path and never touch Context.
     */
    private val mockContext: Context = mockk<Context>(relaxed = true)

    private fun makeRepo(): ActiveModelRepository {
        return ActiveModelRepository(fakePrefs, mockContext, staticRegistry())
    }

    // -- (a) Backend change propagates to activeModelFlow --

    @Test
    fun `activeModelFlow emits new backendId when transcriptionBackend changes`() = runTest {
        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        // Background collector so emissions accumulate
        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent() // let the collector start and receive the initial emission

        // Initial state: default backend with no model
        assertEquals(
            PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND,
            emissions.last().backendId
        )

        // Switch to whisper
        fakePrefs._transcriptionBackend.value = "whisper"
        runCurrent()
        assertEquals("whisper", emissions.last().backendId)

        // Switch to sherpa-onnx
        fakePrefs._transcriptionBackend.value = "sherpa-onnx"
        runCurrent()
        assertEquals("sherpa-onnx", emissions.last().backendId)

        job.cancel()
    }

    @Test
    fun `activeModelFlow carries the new backend's saved model path on backend switch`() = runTest {
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-distil-it"

        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent()

        // Switch to whisper: should pick up sherpaModelPath("whisper")
        fakePrefs._transcriptionBackend.value = "whisper"
        runCurrent()
        assertEquals("/data/models/whisper-distil-it", emissions.last().modelPath)

        job.cancel()
    }

    // -- (b) Per-backend model-path change propagates without reload --

    @Test
    fun `activeModelFlow emits updated modelPath when active backend path changes`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-old"

        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent()

        // Change whisper model path while whisper is the active backend
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-new"
        runCurrent()
        assertEquals(
            "whisper model path change should emit immediately",
            "/data/models/whisper-new",
            emissions.last().modelPath
        )
        assertEquals("whisper", emissions.last().backendId)

        job.cancel()
    }

    @Test
    fun `changing inactive backend path does NOT emit`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-distil-it"

        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent()

        val countBefore = emissions.size

        // Changing parakeet path while whisper is active: should NOT emit
        fakePrefs._sherpaModelPath("sherpa-onnx").value = "/data/models/parakeet-tdt"
        runCurrent()
        assertEquals(
            "Inactive backend path change must not emit",
            countBefore,
            emissions.size
        )

        job.cancel()
    }

    // -- (c) Multiple collectors see the same update --

    @Test
    fun `two collectors of activeModelFlow both see the same propagated update`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-initial"

        val repo = makeRepo()
        val emissionsA = mutableListOf<ActiveModel>()
        val emissionsB = mutableListOf<ActiveModel>()

        val jobA = backgroundScope.launch { repo.activeModelFlow.collect { emissionsA.add(it) } }
        val jobB = backgroundScope.launch { repo.activeModelFlow.collect { emissionsB.add(it) } }
        runCurrent()

        // Mutate the active backend's model path
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/whisper-updated"
        runCurrent()

        // Both collectors should eventually see the update
        assertEquals(
            "Collector A must see the new path",
            "/data/models/whisper-updated",
            emissionsA.last().modelPath
        )
        assertEquals(
            "Collector B must see the same new path",
            "/data/models/whisper-updated",
            emissionsB.last().modelPath
        )
        assertEquals(emissionsA.last().backendId, emissionsB.last().backendId)

        jobA.cancel()
        jobB.cancel()
    }

    // -- Path-derived model name assertions --
    // "gemma4_gguf" reads ggufModelPath and derives the name from the filename.
    // "llm" reads modelPath and derives the name from the filename.
    // These two are distinct backends reading distinct flows (verified against the
    // real dispatch table: grep BACKEND_ID + the "gemma4_gguf" literal in source).

    @Test
    fun `gemma4_gguf backend derives modelName from gguf filename`() = runTest {
        fakePrefs._transcriptionBackend.value = "gemma4_gguf"
        fakePrefs._ggufModelPath.value = "/data/models/gemma-4-e2b-it.gguf"

        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent()

        assertEquals("gemma4_gguf", emissions.last().backendId)
        assertEquals("/data/models/gemma-4-e2b-it.gguf", emissions.last().modelPath)
        // GGUF backend derives name from the filename WITH extension (matches old File(path).name behavior)
        assertEquals("gemma-4-e2b-it.gguf", emissions.last().modelName)

        job.cancel()
    }

    @Test
    fun `llm backend reads modelPath and shows the fixed localized name`() = runTest {
        fakePrefs._transcriptionBackend.value = "llm"
        fakePrefs._modelPath.value = "/data/models/gemma-litert-latest.task"

        val repo = makeRepo()
        val emissions = mutableListOf<ActiveModel>()

        val job = backgroundScope.launch {
            repo.activeModelFlow.collect { emissions.add(it) }
        }
        runCurrent()

        assertEquals("llm", emissions.last().backendId)
        assertEquals("/data/models/gemma-litert-latest.task", emissions.last().modelPath)
        // LLM backend has a fixed localized display name (PR #28 nit 2): the raw filename
        // must NOT leak anymore. mockContext.getString returns "" for any res id, so we
        // assert the absence of filename leakage rather than the exact localized text.
        assertNotEquals("gemma-litert-latest.task", emissions.last().modelName)

        job.cancel()
    }

    // -- Variant-aware model name (TASK-436) --
    // Same shared derivation as the log row: a multi-variant catalog backend
    // surfaces the installed variant's localized title, so the settings label
    // agrees with the Logs UI instead of showing the bare family label.

    @Test
    fun `whisper modelName carries the installed variant title`() = runTest {
        val context = mockk<Context>()
        every { context.getString(R.string.whisper_title) } returns "Whisper"
        every { context.getString(R.string.whisper_small_title) } returns "Whisper Small"
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._sherpaModelPath("whisper").value = "/data/models/sherpa-onnx-whisper-small"

        val repo = ActiveModelRepository(fakePrefs, context, staticRegistry())

        assertEquals("Whisper Small", repo.activeModelFlow.first().modelName)
    }
}
