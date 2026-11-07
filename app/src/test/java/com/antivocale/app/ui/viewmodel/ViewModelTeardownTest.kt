package com.antivocale.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import org.junit.After

/**
 * TASK-961: the ONE ordered teardown for ViewModel suites, replacing the
 * inline copies TASK-960 left in every class under this package. The order
 * IS the contract: every registered viewModelScope and test-owned
 * CoroutineScope dies first, then the mocks go away, then Main resets.
 * Reversing any pair reopens the CI UncaughtExceptionsBeforeTest family
 * TASK-960 closed (pending jobs resume against dead dispatchers and
 * crash into the NEXT class's Main).
 *
 * A JUnit rule is the wrong layer here: rule hooks fire after @After,
 * inside that crash window, so this is a base class instead.
 *
 * Usage: extend it, then register each ViewModel and test-owned scope at
 * its construction site. register returns its argument, so
 * `appScope = register(CoroutineScope(...))` and
 * `viewModel = register(LogsViewModel(...))` read in place; the Settings
 * suites register their [SettingsViewModelTestHelper] the same way (its
 * speaker-ids temp dir is reclaimed after Main resets). Registration at
 * the construction site also keeps the TASK-960 lateinit guard structural:
 * a @Before that dies mid-construction never registers the half-built
 * object, and this teardown still reaches unmockkAll/resetMain.
 *
 * Subclass @After methods run BEFORE this class's (JUnit4 runs the
 * superclass @After last), which is the wrong order for a subclass's own
 * unmocks: put subclass-specific teardown in [afterTeardown] instead, and
 * keep it mock-free, or fold the need into this harness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTeardownTest {

    private val viewModels = mutableListOf<ViewModel>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val helpers = mutableListOf<SettingsViewModelTestHelper>()

    /** Registers [viewModel] for scope cancellation; returns it unchanged. */
    protected fun <T : ViewModel> register(viewModel: T): T {
        viewModels += viewModel
        return viewModel
    }

    /** Registers [scope] for cancellation; returns it unchanged. */
    protected fun <T : CoroutineScope> register(scope: T): T {
        scopes += scope
        return scope
    }

    /**
     * Registers the helper's ViewModel and queues the helper's own
     * filesystem cleanup for the post-reset slot. Internal, like the
     * helper type it accepts (a protected member cannot expose it).
     */
    internal fun register(
        harness: SettingsViewModelTestHelper,
    ): SettingsViewModelTestHelper {
        viewModels += harness.viewModel
        helpers += harness
        return harness
    }

    /**
     * Subclass cleanup that must run after the ordered teardown (temp
     * dirs, singleton re-seeds). Runs last; keep it mock-free.
     */
    protected open fun afterTeardown() {}

    @After
    fun orderedTeardown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scopes.forEach { it.cancel() }
        unmockkAll()
        Dispatchers.resetMain()
        // The helper's cancel half is a no-op by now (its ViewModel was
        // cancelled above); the speaker-ids temp dir is the footprint
        // reclaimed here.
        helpers.forEach { it.cleanup() }
        afterTeardown()
    }
}
