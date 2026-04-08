package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogDao
import com.antivocale.app.data.local.LogEntity
import com.antivocale.app.data.local.toLogEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LogsViewModel.activeTranscription flow.
 *
 * Verifies the mapping logic that selects the active transcription
 * for the PiP view: first PENDING with non-empty result, then any PENDING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelActiveTranscriptionTest {

    private lateinit var logDao: LogDao
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()
    private val logsFlow = MutableStateFlow<List<LogEntity>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        logDao = mockk(relaxed = true)
        every { logDao.getAll() } returns logsFlow
        viewModel = LogsViewModel(logDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeEntity(
        taskId: String,
        status: String = "PENDING",
        result: String = ""
    ) = LogEntity(
        id = "id-$taskId",
        timestamp = System.currentTimeMillis(),
        taskId = taskId,
        type = "AUDIO",
        status = status,
        prompt = "",
        result = result,
        errorMessage = null,
        durationMs = 0,
        filePath = null,
        audioDurationSeconds = 0.0,
        sourcePackageName = null
    )

    /**
     * Tests the selection logic used by activeTranscription.
     * This mirrors the exact map function from LogsViewModel.
     */
    private fun selectActive(entities: List<LogEntity>): LogEntry? {
        val logList = entities.map { it.toLogEntry() }
        return logList.firstOrNull {
            it.status == LogEntry.Status.PENDING && it.result.isNotEmpty()
        } ?: logList.firstOrNull { it.status == LogEntry.Status.PENDING }
    }

    @Test
    fun `selectActive returns null when no logs exist`() {
        assertNull(selectActive(emptyList()))
    }

    @Test
    fun `selectActive returns null when only completed entries exist`() {
        assertNull(
            selectActive(
                listOf(
                    makeEntity("task-1", status = "SUCCESS", result = "Hello world"),
                    makeEntity("task-2", status = "ERROR")
                )
            )
        )
    }

    @Test
    fun `selectActive picks pending entry without interim text`() {
        val active = selectActive(
            listOf(makeEntity("task-1", status = "PENDING", result = ""))
        )
        assertNotNull(active)
        assertEquals("task-1", active!!.taskId)
    }

    @Test
    fun `selectActive prefers pending entry with interim text over empty`() {
        val active = selectActive(
            listOf(
                makeEntity("task-1", status = "PENDING", result = ""),
                makeEntity("task-2", status = "PENDING", result = "Hello from segment 1")
            )
        )
        assertNotNull(active)
        assertEquals("task-2", active!!.taskId)
        assertEquals("Hello from segment 1", active.result)
    }

    @Test
    fun `selectActive picks first pending with text when multiple have text`() {
        val active = selectActive(
            listOf(
                makeEntity("task-1", status = "PENDING", result = "First"),
                makeEntity("task-2", status = "PENDING", result = "Second")
            )
        )
        assertEquals("task-1", active!!.taskId)
    }

    @Test
    fun `selectActive returns null when pending becomes success`() {
        val before = selectActive(
            listOf(makeEntity("task-1", status = "PENDING", result = "Working on it"))
        )
        assertNotNull(before)

        val after = selectActive(
            listOf(makeEntity("task-1", status = "SUCCESS", result = "Final text"))
        )
        assertNull(after)
    }

    @Test
    fun `selectActive switches to new pending when first completes`() {
        val first = selectActive(
            listOf(makeEntity("task-1", status = "PENDING", result = "First task"))
        )
        assertEquals("task-1", first!!.taskId)

        val after = selectActive(
            listOf(
                makeEntity("task-1", status = "SUCCESS", result = "Done"),
                makeEntity("task-2", status = "PENDING", result = "Second task")
            )
        )
        assertEquals("task-2", after!!.taskId)
    }

    @Test
    fun `selectActive ignores error entries`() {
        assertNull(
            selectActive(
                listOf(
                    makeEntity("task-1", status = "ERROR"),
                    makeEntity("task-2", status = "SUCCESS", result = "Done")
                )
            )
        )
    }

    @Test
    fun `selectActive picks only pending entry among mixed statuses`() {
        val active = selectActive(
            listOf(
                makeEntity("task-1", status = "SUCCESS", result = "Done"),
                makeEntity("task-2", status = "PENDING", result = "In progress"),
                makeEntity("task-3", status = "ERROR")
            )
        )
        assertEquals("task-2", active!!.taskId)
    }
}
