package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.antivocale.app.data.VoiceNoteDuplicateGuard
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.transcription.staticRegistry
import com.antivocale.app.util.LocaleManager
import com.antivocale.app.util.SharedAudioHandler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [34])
class LogsViewModelBatchIntakeTest {

    private lateinit var viewModel: LogsViewModel
    private val duplicateGuard: VoiceNoteDuplicateGuard = mockk(relaxed = true)

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
        coEvery { duplicateGuard.hashOnIo(any()) } returns "hash"
        coEvery { duplicateGuard.lookup(any(), any(), any(), any()) } returnsMany listOf(
            hit("task-1", "model-a"),
            hit("task-2", "model-b"),
        )
        viewModel = LogsViewModel(
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
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun hit(existingTaskId: String, modelName: String) =
        VoiceNoteDuplicateGuard.DuplicateHit(
            row = mockk<LogEntity> { every { taskId } returns existingTaskId },
            modelName = modelName,
            contentHash = "hash",
        )

    @Test
    fun `second file's intake waits for the first dialog to resolve`() = runBlocking {
        val uris = listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2"))

        viewModel.transcribeLocalFiles(mockk(relaxed = true), uris)

        // File 1 owns the single dialog slot...
        val first = withTimeout(5000) {
            viewModel.pendingBrowseDuplicate.first { it != null }
        }
        assertEquals("model-a", first?.modelName)

        // ...and file 2 cannot take the slot until it is dismissed (the
        // pick arm: clear without reprocess).
        viewModel.clearPendingBrowseDuplicate()
        val second = withTimeout(5000) {
            viewModel.pendingBrowseDuplicate.first { it?.modelName == "model-b" }
        }
        assertEquals("model-b", second?.modelName)
    }

    @Test
    fun `a second batch queues behind an unresolved first batch`() = runBlocking {
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        val first = withTimeout(5000) {
            viewModel.pendingBrowseDuplicate.first { it != null }
        }
        assertEquals("model-a", first?.modelName)

        // Review W1: a second pick while the first dialog is still pending
        // must not overwrite the single slot; its file queues on the shared
        // intake lock instead.
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/2")))
        delay(200)
        assertEquals("model-a", viewModel.pendingBrowseDuplicate.value?.modelName)

        viewModel.clearPendingBrowseDuplicate()
        val second = withTimeout(5000) {
            viewModel.pendingBrowseDuplicate.first { it?.modelName == "model-b" }
        }
        assertEquals("model-b", second?.modelName)
    }

    @Test
    fun `an abandoned dialog times out and reports the file as not added`() = runBlocking {
        viewModel.browseDialogTimeoutMs = 100
        // A live subscriber keeps the report on the snackbar path; the
        // notification path would drive real NotificationCompat code
        // against this test's mock context.
        val reports = mutableListOf<String>()
        val collector = launch { viewModel.historyError.collect { reports.add(it) } }
        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        withTimeout(5000) { viewModel.pendingBrowseDuplicate.first { it != null } }

        // The pending state must clear and the batch must not park.
        withTimeout(5000) { viewModel.pendingBrowseDuplicate.first { it == null } }
        assertEquals(null, viewModel.pendingBrowseDuplicate.value)
        withTimeout(5000) { while (reports.isEmpty()) delay(10) }
        collector.cancel()
    }

    @Test
    fun `a throwing intake is reported, not only logged`() = runBlocking {
        coEvery {
            SharedAudioHandler.copyToAppStorage(any(), any(), any(), any())
        } throws RuntimeException("fgs start refused")
        val reports = mutableListOf<String>()
        val job = launch { viewModel.historyError.collect { reports.add(it) } }
        // The collector must be subscribed before the intake runs.
        delay(100)

        viewModel.transcribeLocalFiles(mockk(relaxed = true), listOf(Uri.parse("content://picker/1")))
        // The stubbed context resolves the message to an empty string; the
        // emission itself is the behavior under test.
        withTimeout(5000) { while (reports.isEmpty()) delay(10) }
        job.cancel()
    }

    @Test
    fun `empty pick is a no-op`() = runBlocking {
        viewModel.transcribeLocalFiles(mockk<Context>(relaxed = true), emptyList())
        assertEquals(null, viewModel.pendingBrowseDuplicate.value)
    }
}
