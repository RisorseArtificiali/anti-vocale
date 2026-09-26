package com.antivocale.app.transcription

import com.antivocale.app.R
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.ProcessingContextConverter
import com.antivocale.app.data.local.TimedSegmentsConverter
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-677 (GH #92, import half): requestType "subtitle_import" through the
 * real processRequest funnel. The cues must survive onto the row as
 * TimedSegments (the annotated lineage and timed exports depend on them),
 * the row must be marked subtitle-sourced with no model name, and a file
 * with no cues must fail honestly without ever touching a backend.
 */
class TranscriptionOrchestratorSubtitleImportTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** logSuccess needs an existing row to update (the base stubs null). */
    private fun stubExistingRow(taskId: String) {
        coEvery { logDao.getByTaskId(any()) } returns LogEntity(
            id = taskId, timestamp = 1, taskId = taskId,
            type = "TEXT", status = "PROCESSING", prompt = "", result = "")
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.call(
        filePath: String?, context: android.content.Context = mockk(relaxed = true)) =
        orchestrator.processRequest(
            taskId = "imp-1", requestType = "subtitle_import", prompt = "",
            filePath = filePath, source = "share", sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = context, cacheDir = temporaryFolder.root,
            listener = listener, coroutineScope = this)

    @Test
    fun `a handed srt becomes the transcript with cues preserved and no model`() = runTest {
        stubExistingRow("imp-1")
        val file = temporaryFolder.newFile("note.srt")
        file.writeText(
            "1\n00:00:01,000 --> 00:00:03,000\nprima frase\n\n" +
                "2\n00:00:04,500 --> 00:00:06,000\nSPEAKER 1: seconda\n")

        val result = call(file.absolutePath)

        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("prima frase seconda", result.getOrNull())

        // The cues ride the row (segments JSON) exactly as parsed, so History
        // can offer the timed exports immediately.
        coVerify(atLeast = 1) { logDao.update(match { e ->
            e.status == "SUCCESS" &&
                e.segments?.let { TimedSegmentsConverter.fromJson(it).size } == 2 &&
                ProcessingContextConverter.isSubtitleSourced(e.processingContext)
        }) }
        val segments = mutableListOf<TimedSegment>()
        verify {
            listener.onSuccess(
                eq("imp-1"), eq("prima frase seconda"), eq(true), isNull(), any(),
                segments = withArg { segments.addAll(it) })
        }
        assertEquals(
            listOf(
                TimedSegment(1000, 3000, "prima frase"),
                TimedSegment(4500, 6000, "seconda", 0)),
            segments)
        // Honest labeling: no model ever resolves on this path.
        coVerify(exactly = 0) { logDao.setModelName(any(), any()) }
    }

    @Test
    fun `a file with no cues fails honestly and never loads a backend`() = runTest {
        stubExistingRow("imp-1")
        val file = temporaryFolder.newFile("garbage.srt")
        file.writeText("not a subtitle file at all\n")
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getString(R.string.subtitle_import_failed) } returns "bad-subtitle-msg"

        val result = call(file.absolutePath, context)

        assertTrue(result.isFailure)
        coVerify(atLeast = 1) { logDao.update(match { e ->
            e.status == "ERROR" && e.errorMessage == "bad-subtitle-msg"
        }) }
        verify {
            listener.onError(
                eq("imp-1"), eq("SUBTITLE_IMPORT_FAILED"), eq("bad-subtitle-msg"),
                eq(true), eq(false), any())
        }
        // No model was configured, loaded or consulted: the text file IS the
        // source, and the failure has no ASR fallback.
        verify { backendManager wasNot Called }
    }
}
