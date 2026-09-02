package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LogsViewModel.logSuccess overwriting an interim result.
 * The former updateInterimResult public API was removed as dead code: all
 * interim writes go through the orchestrator's private path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelSuccessTest {

    private lateinit var logDao: LogDao
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val testTaskId = "test-task-123"
    private val pendingEntity = LogEntity(
        id = "log-id-1",
        timestamp = System.currentTimeMillis(),
        taskId = testTaskId,
        type = "AUDIO",
        status = "PROCESSING",
        result = ""
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        logDao = mockk(relaxed = true)
        viewModel = LogsViewModel(mockk(relaxed = true), logDao, stubPreferencesManager(), staticRegistry(), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `logSuccess overwrites interim result`() = runTest {
        // Set interim result first
        val entityWithInterim = pendingEntity.copy(result = "Partial text...")
        coEvery { logDao.getByTaskId(testTaskId) } returns entityWithInterim

        viewModel.logSuccess(testTaskId, "Full final transcription.", 2500L)
        testScheduler.advanceUntilIdle()

        coVerify { logDao.update(match { entity ->
            entity.result == "Full final transcription." &&
            entity.status == "SUCCESS" &&
            entity.durationMs == 2500L
        })}
    }
}
