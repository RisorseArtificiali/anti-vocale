package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TASK-939: the word-tap seek read-along gate. The SettingsViewModel seam
 * pins the shipped default (false) and the save landing on the preference
 * store and back out through the exposure flow; the HistoryTab surface
 * reads the same preference through LogsViewModel, so a default flip or a
 * broken save fails here before any UI can drift. The flow assertions
 * subscribe first: on a WhileSubscribed StateFlow a bare first() returns
 * the stale initial value before the upstream lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelWordTapSeekTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: SettingsViewModelTestHelper
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        harness = SettingsViewModelTestHelper()
        fakePrefs = harness.fakePrefs
        viewModel = harness.viewModel
    }

    @After
    fun tearDown() {
        harness.cleanup()
        Dispatchers.resetMain()
    }

    @Test
    fun `the word-tap seek exposure starts at the shipped default`() {
        assertEquals(PreferencesManager.DEFAULT_WORD_TAP_SEEK, viewModel.wordTapSeek.value)
        assertEquals(false, fakePrefs._wordTapSeek.value)
    }

    @Test
    fun `saving the word-tap seek gate lands on the preference store and the exposure`() = runTest {
        val seen = mutableListOf<Boolean>()
        val job = launch { viewModel.wordTapSeek.take(3).toList(seen) }
        runCurrent()

        viewModel.saveWordTapSeek(true)
        runCurrent()
        viewModel.saveWordTapSeek(false)
        runCurrent()
        job.join()

        assertEquals(listOf(false, true, false), seen)
        assertEquals(false, fakePrefs._wordTapSeek.value)
    }
}
