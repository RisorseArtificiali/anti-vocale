package com.antivocale.app.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * TASK-579 fold arms (AC4): the repetition-loop guard and the F4/F5
 * degradation must deliver the first pass with the skip token and the
 * fast model's credit, never the destroyed refinement. The success-arm
 * fold is exercised through [TranscriptionOrchestrator.refinementFoldSuccess]
 * directly because a completed phase 2 needs a real backend load; the
 * F4 arm runs the full orchestrator with a phase-2 load failure.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TranscriptionOrchestratorDualGuardTest : TranscriptionOrchestratorTestBase() {

    private val fastText =
        "prima passata completa con abbastanza parole da sembrare un vocale vero"
    private val loopText = (1..40).joinToString(" ") { "¡Muy bien!" }
    private val cleanRefined =
        "testo rifinito dalla seconda passata, pulito e completo di tutto"

    private var tempFilesDir: File? = null

    @After
    fun deleteTempFilesDir() {
        tempFilesDir?.deleteRecursively()
        tempFilesDir = null
    }

    private fun firstPass(partial: Boolean = false) = FirstPassOutcome(
        text = fastText,
        processing = ProcessingContext(decodePath = "whole_file", backendId = "nemotron-streaming"),
        isPartial = partial,
    )

    /**
     * TASK-740: the shared two-pass F4 fixture (the setUpGigaamWholeFileFixture
     * precedent): a real nemotron variant dir so the streaming entry resolves
     * as installed, the streaming mock with BOTH transcribe methods stubbed
     * (the stub-BOTH gotcha; the relaxed streaming default would return blank
     * and phase 1 would skip as F2/F3), and the whole-file preprocessing
     * stub. The phase-2 (accurate) load then fails natively, exactly the F4
     * shape both twins assert over. [pin] selects TASK-740's refine-model
     * pin (null = unset, the pre-740 behavior).
     */
    private suspend fun runTwoPassF4(
        taskId: String,
        pin: String? = null,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Result<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val variantDir = File(context.filesDir, "nemotron/nemotron-3.5-asr-streaming-0.6b-1120ms-int8")
        variantDir.mkdirs()
        tempFilesDir = File(context.filesDir, "nemotron")
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .forEach { File(variantDir, it).writeText("x") }

        every { preferencesManager.refinementEnabled } returns flowOf(true)
        stubDefaultWhisperPreferences()
        // AFTER the helper: mockk's later identical-signature stub wins (the
        // base's own "re-stub after baseSetUp and win" convention).
        if (pin != null) {
            every { preferencesManager.refinementModelBackendId } returns flowOf(pin)
            // The credit's display-name derivation reads the pin's saved
            // path (the same relaxed-Flow trap as the fast model's).
            every { preferencesManager.sherpaModelPath(pin) } returns flowOf("")
        }
        // The F8 display-name credit resolves the fast model's saved path
        // (a relaxed Flow explodes on first(), the base's documented trap).
        every { preferencesManager.sherpaModelPath("nemotron-streaming") } returns flowOf("")
        val streaming = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "nemotron-streaming"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            // Null cap (the house pattern for whole-file mocks): a relaxed 0
            // routes the request into the pipeline path with 0 s chunks and
            // phase 1 dies before preprocessing.
            every { maxChunkDurationSeconds } returns null
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns streaming
        coEvery { streaming.transcribeAudio(any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = fastText))
        coEvery { streaming.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = fastText))
        stubPreprocessing(listOf(FloatArray(1600)), 8.0)

        return orchestrator.processRequest(
            taskId = taskId,
            requestType = "audio",
            prompt = "",
            filePath = "/path/to/audio.wav",
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = scope,
        )
    }

    @Test
    fun `guard arm delivers the first pass with the loop token and fast credit`() {
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass(), TranscriptionResult(text = loopText)).getOrThrow()

        assertEquals(fastText, delivered.text)
        assertEquals(
            DualRefinementPolicy.SKIP_REFINE_LOOP,
            delivered.firstPass?.skipOutcome?.token)
        // TASK-582: the measured loop values ride the same record, so a
        // field firing is tunable after the fact.
        val metrics = delivered.firstPass?.skipOutcome?.loopMetrics
        assertNotNull(metrics)
        assertTrue("compression= and ngram= in: $metrics",
            metrics!!.startsWith("compression=") && metrics.contains("ngram="))
        assertEquals(
            "nemotron-streaming",
            delivered.firstPass?.processing?.backendId)
    }

    @Test
    fun `clean arm keeps the refined text and carries the first pass untouched`() {
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass(), TranscriptionResult(text = cleanRefined)).getOrThrow()

        assertEquals(cleanRefined, delivered.text)
        assertEquals(fastText, delivered.firstPass?.text)
        assertNull(delivered.firstPass?.skipOutcome?.token)
    }

    @Test
    fun `a partial first pass stays partial when the guard fires`() {
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass(partial = true), TranscriptionResult(text = loopText)).getOrThrow()

        assertTrue(delivered.isPartial)
    }

    @Test
    fun `the pinned refinement model reaches the phase-2 load request`() = runTest {
        // TASK-740 (GH #127): with the pin set to a DIFFERENT backend than
        // the active default (whisper), the phase-2 LOAD targets it even
        // when the load itself fails on the JVM: the observable is the
        // credit setModelName, which must name the PINNED model. A pin
        // equal to the active backend would be indistinguishable from
        // inheritance (the review's vacuity finding).
        val result = runTwoPassF4(taskId = "dual-740", pin = "gigaam", scope = this)

        assertEquals(fastText, result.getOrThrow())
        coVerify {
            logDao.setModelName(eq("dual-740"), match { it.contains("GigaAM", ignoreCase = true) })
        }
    }

    @Test
    fun `phase 2 load failure delivers the first pass through the full funnel`() = runTest {
        val outcome = slot<String?>()
        val result = runTwoPassF4(taskId = "dual-f4", scope = this)

        assertEquals(fastText, result.getOrThrow())
        // The FULL funnel: the delivery rides onSuccess (never a silent
        // return) and the not-refined caption tells the truth (the F4 arm
        // is a degraded delivery, not a completed refinement).
        verify {
            listener.onSuccess(
                eq("dual-f4"), eq(fastText), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), captureNullable(outcome))
        }
        // The delivered first pass is captioned not-refined, never credited
        // as a completed refinement.
        assertEquals(DualRefinementPolicy.NOT_REFINED, outcome.captured)
    }
}
