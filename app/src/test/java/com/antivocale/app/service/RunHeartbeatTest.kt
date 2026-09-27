package com.antivocale.app.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TASK-684: the persisted liveness heartbeat. The lifecycle contract the
 * classifier's gap evidence depends on: seeded and ticked by the owning run,
 * cleared by the owning run only, invisible once cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RunHeartbeatTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `read is null before any run touches it`() {
        assertNull(RunHeartbeat.read(context))
    }

    @Test
    fun `touch carries the task id and a timestamp`() {
        RunHeartbeat.touch(context, "task-1")
        val first = RunHeartbeat.read(context)
        assertNotNull(first)
        assertEquals("task-1", first!!.taskId)
        Thread.sleep(5)
        RunHeartbeat.touch(context, "task-1")
        val second = RunHeartbeat.read(context)
        assertEquals("task-1", second!!.taskId)
        assertTrue("touch refreshes the timestamp", second.timestampMs >= first.timestampMs)
    }

    @Test
    fun `clear by the owning task removes the evidence`() {
        RunHeartbeat.touch(context, "task-1")
        RunHeartbeat.clear(context, "task-1")
        assertNull(RunHeartbeat.read(context))
    }

    @Test
    fun `clear by another task leaves the heartbeat alone`() {
        // A stale clear (a race from a previous run's finally) must not erase
        // the new run's evidence; the clear matches on the owning task id.
        RunHeartbeat.touch(context, "task-2")
        RunHeartbeat.clear(context, "task-1")
        val heartbeat = RunHeartbeat.read(context)
        assertNotNull(heartbeat)
        assertEquals("task-2", heartbeat!!.taskId)
    }

    @Test
    fun `touch overwrites a previous run's heartbeat`() {
        // Sequential runs share one slot; the newest run owns it.
        RunHeartbeat.touch(context, "task-1")
        RunHeartbeat.touch(context, "task-2")
        assertEquals("task-2", RunHeartbeat.read(context)!!.taskId)
    }
}
