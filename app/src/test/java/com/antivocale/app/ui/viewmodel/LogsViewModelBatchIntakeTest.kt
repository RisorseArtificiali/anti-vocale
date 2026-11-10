package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.antivocale.app.data.VoiceNoteDuplicateGuard
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.receiver.SubtitleChoice
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.util.LocaleManager
import com.antivocale.app.util.SharedAudioHandler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-927 review V1: the multi pick's serialization. Two files in one pick
 * must not race for the single-slot [LogsViewModel.pendingBrowseDuplicate]:
 * file 2's intake reaches the duplicate guard only after file 1's dialog
 * resolves. Without the gate the second pending state overwrote the first
 * and the first file's dialog (and its enqueue) was lost silently.
 */
@RunWith(RobolectricTestRunner::class)
class LogsViewModelBatchIntakeTest : ViewModelTeardownTest() {

    private lateinit var viewModel: LogsViewModel
    private val duplicateGuard: VoiceNoteDuplicateGuard = mockk(relaxed = true)

    // A field (not the constructor default) so the harness cancels it.
    private val appScope = register(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Before
    fun setup() {
        // Real unconfined Main: the intake hops are stubbed to instant work,
        // so the test can synchronize on the pending state itself.
        Dispatchers.setMain(Dispatchers.Unconfined)
        mockkObject(SharedAudioHandler)
        mockkObject(LocaleManager)
        every { LocaleManager.updateContextLocale(any()) } answers { firstArg() }
        every {
            SharedAudioHandler.copyToAppStorage(any(), any(), any(), any())
        } returns SharedAudioHandler.CopyResult.Success("/tmp/browse-copy")
        // The reclaim helper must run for real or the TASK-934 deletion
        // test would assert against a no-op stub.
        every { SharedAudioHandler.deleteStagedCopy(any()) } answers { callOriginal() }
        coEvery { duplicateGuard.hashOnIo(any()) } returns "hash"
        coEvery { duplicateGuard.lookup(any(), any(), any(), any()) } returnsMany listOf(
            hit("task-1", "model-a"),
            hit("task-2", "model-b"),
        )
        viewModel = register(LogsViewModel(
            mockk(relaxed = true),
            mockk(relaxed = true),
            stubPreferencesManager(),
            staticRegistry(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            orchestrator = mockk(relaxed = true),
            duplicateGuard = duplicateGuard,
            appScope = appScope,
        ))
    }

    /** Mock-free leftovers reclaim, after the ordered harness teardown. */
    override fun afterTeardown() {
        stagedFile?.delete()
        stagedFile = null
    }

    private fun hit(existingTaskId: String, modelName: String) =
        VoiceNoteDuplicateGuard.DuplicateHit(
            row = mockk<LogEntity> { every { taskId } returns existingTaskId },
            modelName = modelName,
            contentHash = "hash",
        )

    /** Pins a non-duplicate file's intake completion at the subtitle probe
     *  (the F5 boundary): the probe call is the observable we assert on. */
    private fun stubSubtitleProbe() {
        mockkObject(SubtitleChoice)
        every {
            SubtitleChoice.offerIfTracks(any(), any(), any(), any(), any(), any(), any())
        } returns true
    }

    /** A subscriber awaited before the triggering action, so a report routes
     *  to the snackbar path instead of the notification path the mock
     *  context cannot run. Join it after. Under the Unconfined Main the
     *  launch subscribes before its first suspension point, so subscription
     *  is deterministic without a timed sleep. */
    private fun awaitFirstReport(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job =
        scope.launch { viewModel.historyError.first() }

    /** The ViewModel's error path guards on subscriptionCount > 0 before
     *  emitting (it skips orphans). On a slow CI runner the runBlocking
     *  event loop may not have dispatched the collector's launch before
     *  transcribeLocalFiles fires the error, so the emission is skipped
     *  and first() never returns. Yielding here gives the event loop one
     *  scheduling quantum to run the collector's subscription before the
     *  caller starts the work that emits (the root-cause fix for the CI
     *  flake). */
    private suspend fun awaitSubscribedReport(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job {
        val job = scope.launch { viewModel.historyError.first() }
        kotlinx.coroutines.yield()
        return job
    }

    @Test
    fun `second file's intake waits for the first dialog to resolve`() = runBlocking {
        val uris = listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2"))

        viewModel.transcribeLocalFiles(mockk(relaxed = true), uris)

        // TASK-960: the wall budgets are 30s because the CI fdroid runner
        // reds a 5s budget under load while the test logic is correct (4/4
        // green in isolation on the same HEAD). The leak half of TASK-960
        // landed as the teardown scope cancels; these budgets stay raised
        // for the slow-runner half (a real deadlock still fails in 30s).
        // File 1 owns the single dialog slot...
        val first = withTimeout(30_000) {
            viewModel.pendingBrowseDuplicate.first { it != null }
        }
        assertEquals("model-a", first?.modelName)

        // ...and file 2 cannot take the slot until it is dismissed (the
        // pick arm: clear without reprocess).
        viewModel.clearPendingBrowseDuplicate()
        val second = withTimeout(30_000) {
            viewModel.pendingBrowseDuplicate.first { it?.modelName == "model-b" }
        }
        assertEquals("model-b", second?.modelName)
    }

    @Test
    fun `a second batch queues behind an unresolved first batch`() = runBlocking {
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        val first = withTimeout(30_000) {
            viewModel.pendingBrowseDuplicate.first { it != null }
        }
        assertEquals("model-a", first?.modelName)

        // Review W1/Y6: a second pick's DUPLICATE queues on the shared
        // intake lock behind the first dialog (fresh files proceed; the
        // "fresh file proceeds" test pins that half).
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/2")))
        delay(200)
        assertEquals("model-a", viewModel.pendingBrowseDuplicate.value?.modelName)

        viewModel.clearPendingBrowseDuplicate()
        val second = withTimeout(30_000) {
            viewModel.pendingBrowseDuplicate.first { it?.modelName == "model-b" }
        }
        assertEquals("model-b", second?.modelName)
    }

    @Test
    fun `a throwing intake is reported, not only logged`() = runBlocking {
        coEvery {
            SharedAudioHandler.copyToAppStorage(any(), any(), any(), any())
        } throws RuntimeException("fgs start refused")
        val report = awaitSubscribedReport(this)

        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        // The stubbed context resolves the message to an empty string; the
        // emission itself is the behavior under test.
        withTimeout(30_000) { report.join() }
    }

    @Test
    fun `a second batch's fresh file proceeds while a first dialog pends`() = runBlocking {
        // Batch 1's file is a duplicate, batch 2's file is not.
        coEvery { duplicateGuard.lookup(any(), any(), any(), any()) } returnsMany listOf(
            hit("task-1", "model-a"),
            null,
        )
        stubSubtitleProbe()

        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        withTimeout(30_000) { viewModel.pendingBrowseDuplicate.first { it != null } }

        // Review Y6: the second pick's fresh file must not queue behind the
        // pending dialog (the whole-pass lock used to park it there).
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/2")))
        withTimeout(30_000) {
            verify(timeout = 10_000) {
                SubtitleChoice.offerIfTracks(
                    any(), any(), eq("/tmp/browse-copy"), any(), any(), any(), any())
            }
        }
        // ...and the pending dialog was not displaced.
        assertEquals("model-a", viewModel.pendingBrowseDuplicate.value?.modelName)
    }

    @Test
    fun `empty pick is a no-op`() = runBlocking {
        viewModel.transcribeLocalFiles(mockk<Context>(relaxed = true), emptyList())
        assertEquals(null, viewModel.pendingBrowseDuplicate.value)
    }

    /** A duplicate dialog over a REAL temp file, so the Open arm's deletion
     *  (TASK-934) has something observable to reclaim. Removed in
     *  afterTeardown. */
    private var stagedFile: java.io.File? = null

    private fun stagedRealFile(): java.io.File {
        val staged = kotlin.io.path.createTempFile("task934").toFile()
        staged.writeText("x")
        stagedFile = staged
        every {
            SharedAudioHandler.copyToAppStorage(any(), any(), any(), any())
        } returns SharedAudioHandler.CopyResult.Success(staged.absolutePath)
        return staged
    }

    @Test
    fun `TASK-934 the open arm deletes the staged copy`() = runBlocking {
        val staged = stagedRealFile()
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        withTimeout(30_000) { viewModel.pendingBrowseDuplicate.first { it != null } }

        viewModel.onBrowseDuplicateOpen("task-1")
        withTimeout(30_000) {
            while (staged.exists()) delay(20)
        }
        assertEquals(null, viewModel.pendingBrowseDuplicate.value)
    }

    @Test
    fun `TASK-934 the reprocess arm keeps the staged copy for the enqueue`() = runBlocking {
        val staged = stagedRealFile()
        stubSubtitleProbe()
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        val hit = withTimeout(30_000) { viewModel.pendingBrowseDuplicate.first { it != null } }!!

        viewModel.clearPendingBrowseDuplicate()
        hit.continueRequest()
        withTimeout(30_000) {
            verify(timeout = 10_000) {
                SubtitleChoice.offerIfTracks(
                    any(), any(), eq(staged.absolutePath), any(), any(), any(), any())
            }
        }
        // The probe owns the request (stubbed true) and the file survives
        // for the flow that consumes it.
        assertTrue(staged.exists())
    }
}
