package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogDao
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
 * Unit tests for LogsViewModel highlight feature.
 *
 * Verifies the one-shot highlight signal used by notification-to-content navigation:
 * - highlightLogEntry sets the signal
 * - clearHighlight resets it
 * - Multiple calls: last one wins
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelHighlightTest {

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
    fun `highlightTaskId is initially null`() = runTest {
        assertNull(viewModel.highlightTaskId.value)
    }

    @Test
    fun `highlightLogEntry sets highlightTaskId`() = runTest {
        viewModel.highlightLogEntry("task-abc")
        assertEquals("task-abc", viewModel.highlightTaskId.value)
    }

    @Test
    fun `clearHighlight resets to null`() = runTest {
        viewModel.highlightLogEntry("task-abc")
        viewModel.clearHighlight()
        assertNull(viewModel.highlightTaskId.value)
    }

    @Test
    fun `last highlight wins on rapid calls`() = runTest {
        viewModel.highlightLogEntry("task-1")
        viewModel.highlightLogEntry("task-2")
        viewModel.highlightLogEntry("task-3")
        assertEquals("task-3", viewModel.highlightTaskId.value)
    }

    @Test
    fun `clearHighlight is safe when already null`() = runTest {
        // Should not throw
        viewModel.clearHighlight()
        assertNull(viewModel.highlightTaskId.value)
    }

    @Test
    fun `highlight can be set again after clear`() = runTest {
        viewModel.highlightLogEntry("task-1")
        viewModel.clearHighlight()
        viewModel.highlightLogEntry("task-2")
        assertEquals("task-2", viewModel.highlightTaskId.value)
    }
}
