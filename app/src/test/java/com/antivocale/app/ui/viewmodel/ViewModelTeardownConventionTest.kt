package com.antivocale.app.ui.viewmodel

import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * TASK-961: the meta guard over the ui/viewmodel suites, in the same
 * spirit as the repo's convention scripts. A test file that constructs a
 * ViewModel must not own its own unmockkAll/resetMain teardown: that
 * ordering is the [ViewModelTeardownTest] contract, and an inline copy is
 * one forgotten cancel away from the CI UncaughtExceptionsBeforeTest
 * family TASK-960 closed. The forget-mode cannot come back silently.
 *
 * The scan reads the test sources at runtime through the shared [RepoFiles]
 * locator; when the tree is not readable from the test cwd the test skips
 * instead of false-failing.
 */
class ViewModelTeardownConventionTest {

    /**
     * Matches direct constructions (LogsViewModel(, ModelViewModel() and
     * the Settings construction helper alike; a type mention without a
     * call does not vote. KNOWN BLIND SPOT: a suite that builds its
     * ViewModel behind a factory defined in ANOTHER file contains no
     * construction match here, and the factory file contains no
     * unmockkAll, so both halves miss; every current suite constructs
     * directly. If a shared factory ever appears, move the construction
     * match to the factory's own file or track its callers here.
     */
    private val viewModelConstruction = Regex("""\w*ViewModel\w*\(""")

    @Test
    fun `viewmodel suites own their teardown through the harness`() {
        // FAIL CLOSED (review round): a resolve error propagates instead of
        // silently skipping the whole scan, because a violated suite plus a
        // drifted cwd would otherwise disable the guard with no signal.
        val dir = RepoFiles.resolve("src/test/java/com/antivocale/app/ui/viewmodel")

        val violations = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") && it.name != "ViewModelTeardownTest.kt" }
            .map { it.name to it.readText() }
            .filter { (_, text) ->
                // STRICTER by design (review round): a construction match
                // WITHOUT the harness is a violation even when the file has
                // no unmockkAll/resetMain at all, because "no teardown" is
                // the primary regression mode (orphaned jobs leak across
                // the class boundary). A suite delegating teardown to a
                // shared factory must still extend the harness at its call
                // site or it reads as a false positive: that converts the
                // factory blind spot from a silent miss into a loud fix.
                viewModelConstruction.containsMatchIn(text) &&
                    !text.contains(HARNESS_DECLARATION)
            }
            .map { (name, _) -> name }
            .sorted()
            .toList()

        assertTrue(
            "ViewModel tests owning their teardown instead of $HARNESS_DECLARATION: $violations. " +
                "Extend $HARNESS_DECLARATION and register the ViewModel and every test-owned scope " +
                "at the construction site instead.",
            violations.isEmpty(),
        )
    }

    private companion object {
        /** The class DECLARATION, not a bare mention: a KDoc naming the
         *  harness must not exempt a file from the scan. */
        const val HARNESS_DECLARATION = ": ViewModelTeardownTest"
    }
}
