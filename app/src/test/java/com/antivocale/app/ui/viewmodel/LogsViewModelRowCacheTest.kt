package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.local.LogDao
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TASK-612: the per-row transcript flows must not pin their payload after the
 * collector goes away. stateIn launches its sharing coroutine into
 * viewModelScope eagerly and an idle WhileSubscribed coroutine never
 * completes, so the scope's job tree keeps the flow reachable until
 * onCleared; the only thing that returns the transcript string to GC is the
 * finite replayExpirationMillis resetting the value to the null initial.
 * Virtual-time test on [LogsViewModel.firstPassFlow] (its chain has no
 * real-dispatcher hop, so virtual time controls every step): subscribe, see
 * the value, unsubscribe, expire, and assert the value is null again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelRowCacheTest {

    private lateinit var logDao: LogDao
    private lateinit var viewModel: LogsViewModel
    private val testDispatcher = StandardTestDispatcher()

    /** DAO-side source the row flow subscribes to while collected. */
    private val firstPassSource = MutableStateFlow<String?>("parola")

    /** Cues source for the annotated flow (TimedSegmentsConverter's schema:
     *  top-level array, startMs/endMs/text, speaker required for a non-null
     *  annotation). */
    private val segmentsSource = MutableStateFlow<String?>("[{\"startMs\":0,\"endMs\":1000,\"text\":\"parola\",\"speaker\":0}]")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        logDao = mockk(relaxed = true)
        every { logDao.getAll() } returns MutableStateFlow(emptyList())
        every { logDao.getFirstPass(any()) } returns firstPassSource
        every { logDao.getSegments(any()) } returns segmentsSource
        viewModel = LogsViewModel(mockk(relaxed = true), logDao, stubPreferencesManager(), com.antivocale.app.transcription.staticRegistry(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `row flow releases its payload after the collector is gone past the expiration`() = runTest {
        val flow = viewModel.firstPassFlow("row-1")
        val collector = launch { flow.collect { } }
        advanceTimeBy(1_000)
        assertEquals("parola", flow.value)
        // Collapse: the collector goes away.
        collector.cancel()
        // Still inside the stop timeout: the cached value persists.
        advanceTimeBy(4_000)
        assertEquals("parola", flow.value)
        // Past the 5s stop timeout but BEFORE the 5s replay expiration:
        // the reset must not have fired yet. This checkpoint is what
        // separates the configured 5s+5s from a regression to a zero
        // expiration (reset-at-stop), which would already read null here.
        advanceTimeBy(1_000)
        assertEquals("parola", flow.value)
        // Past stop timeout + replay expiration (the abandonment itself
        // processes asynchronously, so the deadlines sit past the nominal
        // 10s; the margin absorbs that shift): the value resets to the
        // null initial, dropping the transcript payload.
        advanceTimeBy(7_000)
        assertNull(flow.value)
    }

    @Test
    fun `re-collecting an expired row flow re-reads and re-emits`() = runTest {
        val flow = viewModel.firstPassFlow("row-2")
        val first = launch { flow.collect { } }
        advanceTimeBy(1_000)
        assertEquals("parola", flow.value)
        first.cancel()
        advanceTimeBy(13_000)
        assertNull(flow.value)
        val second = launch { flow.collect { } }
        advanceTimeBy(1_000)
        assertEquals("parola", flow.value)
        second.cancel()
    }

    /**
     * The annotated flow shares [LogsViewModel.rowSharingStarted] but its
     * chain hops to Dispatchers.Default for the JSON decode, which virtual
     * time does not control: bootstrap the emission with bounded REAL
     * waits (runCurrent + Thread.sleep, no virtual-clock advance), then
     * let the virtual clock drive the expiry. Guards against a partial
     * revert that pins transcripts through the annotated flow only.
     */
    @Test
    fun `annotated row flow releases its payload too`() = runTest {
        val flow = viewModel.speakerAnnotatedFlow("row-3")
        val collector = launch { flow.collect { } }
        var tries = 0
        while (flow.value == null && tries < 200) {
            testScheduler.runCurrent()
            if (flow.value == null) Thread.sleep(5)
            tries++
        }
        // The annotation is the speaker prefix + text; pin the text part.
        assertTrue(flow.value?.contains("parola") == true)
        collector.cancel()
        advanceTimeBy(13_000)
        assertNull(flow.value)
    }
}
