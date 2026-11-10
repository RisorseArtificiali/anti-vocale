package com.antivocale.app.byteman

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context

/**
 * TASK-387: proves the Byteman agent co-loads with Robolectric in the same
 * Test JVM (needed by TASK-389, whose freeze targets a Room/notification leaf
 * under Robolectric). Runs only under -Pbyteman; the non-agent Robolectric
 * combination is already covered by the whole existing Robolectric suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BytemanRobolectricColoadTest {

    @Before
    fun requireAgent() {
        assumeTrue(System.getProperty("byteman.agent") == "true")
    }

    @Test
    fun `robolectric boots in the agent JVM and resolves the app package`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.antivocale.app", context.packageName.removeSuffix(".debug"))
    }
}
