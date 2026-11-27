package com.antivocale.app.testing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.ExternalModelImportOperations
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.service.InferenceService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The session control ops (session_pause, session_cancel) deliver the
 * production control intents; a device latency proof is only as good as the
 * intent it sends, so each op is pinned to its action and task id.
 */
@RunWith(RobolectricTestRunner::class)
class TestSpiControlOpsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakePreferencesManager()
    private val ops = TestSpiOps(
        fake, ExternalModelStore(fake), mockk<ExternalModelImportOperations>(relaxed = true), appContext = app)

    @Test
    fun `session_cancel sends the per-task cancel for its task`() = runTest {
        val json = JSONObject(ops.handle(TestSpiOps.OP_SESSION_CANCEL, value = "t1"))

        assertEquals("session_cancel", json.getString("op"))
        assertEquals("t1", json.getString("taskId"))
        assertTrue(json.getBoolean("dispatched"))
        val started = shadowOf(app).nextStartedService
        assertEquals(InferenceService.ACTION_CANCEL_TASK, started.action)
        assertEquals("t1", started.getStringExtra(InferenceService.EXTRA_CANCEL_TASK_ID))
    }

    @Test
    fun `session_pause sends the pause for its task`() = runTest {
        val json = JSONObject(ops.handle(TestSpiOps.OP_SESSION_PAUSE, value = "t2"))

        assertEquals("session_pause", json.getString("op"))
        assertTrue(json.getBoolean("dispatched"))
        val started = shadowOf(app).nextStartedService
        assertEquals(InferenceService.ACTION_PAUSE_TASK, started.action)
        assertEquals("t2", started.getStringExtra(InferenceService.EXTRA_PAUSE_TASK_ID))
    }

    @Test
    fun `a blank task id is refused before anything is sent`() = runTest {
        for (op in listOf(TestSpiOps.OP_SESSION_CANCEL, TestSpiOps.OP_SESSION_PAUSE)) {
            val json = JSONObject(ops.handle(op, value = " "))
            assertTrue(json.getString("error").contains("taskId"))
        }
        assertEquals(null, shadowOf(app).nextStartedService)
    }
}
