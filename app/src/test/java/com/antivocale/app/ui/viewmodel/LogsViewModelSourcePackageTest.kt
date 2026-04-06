package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that LogsViewModel.logRequest() correctly persists
 * the sourcePackageName parameter through to the LogDao insert call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelSourcePackageTest {

    private lateinit var logDao: LogDao
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        logDao = mockk(relaxed = true)
        viewModel = LogsViewModel(logDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `logRequest with sourcePackageName inserts entity with that value`() = runTest {
        viewModel.logRequest(
            taskId = "task-1",
            type = LogEntry.Type.AUDIO,
            prompt = "transcribe",
            sourcePackageName = "com.whatsapp"
        )
        testScheduler.advanceUntilIdle()

        coVerify { logDao.insert(match { it.sourcePackageName == "com.whatsapp" }) }
    }

    @Test
    fun `logRequest without sourcePackageName inserts entity with null`() = runTest {
        viewModel.logRequest(
            taskId = "task-2",
            type = LogEntry.Type.TEXT,
            prompt = "summarize"
        )
        testScheduler.advanceUntilIdle()

        coVerify { logDao.insert(match { it.sourcePackageName == null }) }
    }

    @Test
    fun `logRequest with explicit null sourcePackageName inserts entity with null`() = runTest {
        viewModel.logRequest(
            taskId = "task-3",
            type = LogEntry.Type.AUDIO,
            prompt = "transcribe",
            sourcePackageName = null
        )
        testScheduler.advanceUntilIdle()

        coVerify { logDao.insert(match { it.sourcePackageName == null }) }
    }

    @Test
    fun `logRequest preserves all other fields when sourcePackageName is provided`() = runTest {
        viewModel.logRequest(
            taskId = "task-4",
            type = LogEntry.Type.AUDIO,
            prompt = "a voice note",
            filePath = "/path/to/audio.m4a",
            audioDurationSeconds = 12.5,
            sourcePackageName = "com.telegram.messenger"
        )
        testScheduler.advanceUntilIdle()

        coVerify {
            logDao.insert(match {
                it.taskId == "task-4" &&
                it.type == "AUDIO" &&
                it.status == "PENDING" &&
                it.prompt == "a voice note" &&
                it.filePath == "/path/to/audio.m4a" &&
                it.audioDurationSeconds == 12.5 &&
                it.sourcePackageName == "com.telegram.messenger"
            })
        }
    }
}
