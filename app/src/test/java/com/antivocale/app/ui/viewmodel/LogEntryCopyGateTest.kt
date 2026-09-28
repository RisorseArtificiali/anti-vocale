package com.antivocale.app.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-711 (GH #123): the copy-gate pair. hasCompletedResult stays the
 * terminal contract (SUCCESS with text: preview and Share ride it);
 * hasCopyableResult additionally unlocks Copy on the interim state
 * (PROCESSING with text: mid-ASR progressive saves or the summary tail;
 * interim copy delivers what the row displays, which may be partial).
 */
class LogEntryCopyGateTest {

    private fun entry(status: LogEntry.Status, result: String) =
        LogEntry(
            id = "t", timestamp = 0L, taskId = "t", type = LogEntry.Type.AUDIO,
            status = status, result = result)

    @Test
    fun `completed result satisfies both gates`() {
        val e = entry(LogEntry.Status.SUCCESS, "text")
        assertTrue(e.hasCompletedResult)
        assertTrue(e.hasCopyableResult)
    }

    @Test
    fun `interim processing text is copyable but not completed`() {
        val e = entry(LogEntry.Status.PROCESSING, "interim text")
        assertFalse(e.hasCompletedResult)
        assertTrue("the interim transcript must be copyable (GH #123)", e.hasCopyableResult)
    }

    @Test
    fun `empty results and non-delivery states never unlock copy`() {
        for (status in listOf(
            LogEntry.Status.PROCESSING,
            LogEntry.Status.SUCCESS,
            LogEntry.Status.ERROR,
            LogEntry.Status.QUEUED)) {
            val e = entry(status, "")
            assertFalse("empty: $status", e.hasCopyableResult)
            assertFalse("empty: $status", e.hasCompletedResult)
        }
        val failed = entry(LogEntry.Status.ERROR, "text")
        assertFalse("ERROR text is not a transcript delivery", failed.hasCopyableResult)
    }
}
