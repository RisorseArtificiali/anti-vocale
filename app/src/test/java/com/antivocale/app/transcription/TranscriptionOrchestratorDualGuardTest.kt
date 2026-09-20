package com.antivocale.app.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `guard arm delivers the first pass with the loop token and fast credit`() {
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass(), TranscriptionResult(text = loopText)).getOrThrow()

        assertEquals(fastText, delivered.text)
        assertEquals(
            DualRefinementPolicy.SKIP_REFINE_LOOP,
            delivered.firstPass?.refinementFailedToken)
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
        assertNull(delivered.firstPass?.refinementFailedToken)
    }

    @Test
    fun `a partial first pass stays partial when the guard fires`() {
        val delivered = orchestrator.refinementFoldSuccess(
            firstPass(partial = true), TranscriptionResult(text = loopText)).getOrThrow()

        assertTrue(delivered.isPartial)
    }

    @Test
    fun `phase 2 load failure delivers the first pass through the full funnel`() = runTest {
        // The streaming entry resolves as installed: a real directory with
        // the catalog variant's file names under the Robolectric filesDir
        // (the ModelManagerCatalogTest pattern; a mocked filesDir is not
        // stubbable).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val variantDir = File(context.filesDir, "nemotron/nemotron-3.5-asr-streaming-0.6b-1120ms-int8")
        variantDir.mkdirs()
        tempFilesDir = File(context.filesDir, "nemotron")
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .forEach { File(variantDir, it).writeText("x") }

        every { preferencesManager.refinementEnabled } returns flowOf(true)
        stubDefaultWhisperPreferences()
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
        // The single-chunk path routes through the streaming variant so
        // backends can emit partials; the relaxed default would return a
        // blank result and phase 1 would skip as F2/F3.
        coEvery { streaming.transcribeAudioStreaming(any(), any(), any(), any()) } returns
            Result.success(TranscriptionResult(text = fastText))
        stubPreprocessing(listOf(FloatArray(1600)), 8.0)

        val result = orchestrator.processRequest(
            taskId = "dual-f4",
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
            coroutineScope = this,
        )

        // F4: phase 2 (whisper) cannot load against this filesDir; the run
        // still succeeds on the streaming first pass.
        assertEquals(fastText, result.getOrThrow())
        val outcome = slot<String?>()
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
