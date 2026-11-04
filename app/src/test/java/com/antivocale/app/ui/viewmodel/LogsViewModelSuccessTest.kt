package com.antivocale.app.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.transcription.TranscriptionBackendManager
import com.antivocale.app.transcription.staticRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    // TASK-960: a field (not the constructor default) so tearDown can cancel it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        viewModel = LogsViewModel(mockk(relaxed = true), logDao, stubPreferencesManager(), staticRegistry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), orchestrator = io.mockk.mockk(relaxed = true), duplicateGuard = io.mockk.mockk(relaxed = true), appScope = appScope)
    }

    @After
    fun tearDown() {
        // TASK-960: pending viewModelScope jobs must die before unmockkAll/resetMain or they resume against dead objects and crash into the NEXT class's Main dispatcher (the CI UncaughtExceptionsBeforeTest family)
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        appScope.cancel()
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
