package com.antivocale.app.ui.tabs

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Long-press context menu (GH #52): which actions a log entry offers.
 * Mirrors the swipe-action gating: Copy needs text (final or interim,
 * TASK-711), Re-transcribe needs content, Delete always.
 */
class ContextMenuActionsTest {

    private fun entry(
        status: LogEntry.Status = LogEntry.Status.SUCCESS,
        result: String = "text",
        type: LogEntry.Type = LogEntry.Type.AUDIO,
        filePath: String? = "/tmp/a.wav",
    ) = LogEntry(
        taskId = "t", type = type, status = status, result = result, filePath = filePath
    )

    @Test
    fun `successful audio entry offers retranscribe copy delete`() {
        val actions = buildContextMenuActions(entry(), canRetranscribe = true)

        assertEquals(
            listOf(ContextMenuAction.RETRANSCRIBE, ContextMenuAction.COPY, ContextMenuAction.REPORT, ContextMenuAction.DELETE),
            actions
        )
    }

    @Test
    fun `entry without retranscribe handler omits retranscribe`() {
        val actions = buildContextMenuActions(entry(), canRetranscribe = false)

        assertEquals(listOf(ContextMenuAction.COPY, ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `entry with empty result omits copy`() {
        val actions = buildContextMenuActions(entry(result = ""), canRetranscribe = true)

        assertEquals(listOf(ContextMenuAction.RETRANSCRIBE, ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `processing entry with interim text offers copy (TASK-711, GH #123)`() {
        // The interim transcript is deliverable text (mid-ASR saves or the
        // summary tail), so Copy unlocks early; the PROCESSING icon keeps
        // the state marked (TASK-711, GH #123).
        val actions = buildContextMenuActions(
            entry(status = LogEntry.Status.PROCESSING, result = "partial..."),
            canRetranscribe = true,
        )

        assertEquals(listOf(ContextMenuAction.CANCEL, ContextMenuAction.RETRANSCRIBE, ContextMenuAction.COPY, ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `error entry offers report and delete`() {
        val actions = buildContextMenuActions(
            entry(status = LogEntry.Status.ERROR, result = ""),
            canRetranscribe = false,
        )

        assertEquals(listOf(ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `queued entry offers cancel`() {
        val actions = buildContextMenuActions(entry(status = LogEntry.Status.QUEUED, result = ""), canRetranscribe = false)

        assertEquals(listOf(ContextMenuAction.CANCEL, ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `processing entry without text offers cancel but no copy`() {
        // TASK-711: the menu-level false arm (the property is covered in
        // LogEntryCopyGateTest): no text, no Copy, interim or not.
        val actions = buildContextMenuActions(
            entry(status = LogEntry.Status.PROCESSING, result = ""),
            canRetranscribe = true,
        )

        assertEquals(listOf(ContextMenuAction.CANCEL, ContextMenuAction.RETRANSCRIBE, ContextMenuAction.REPORT, ContextMenuAction.DELETE), actions)
    }

    @Test
    fun `terminal entries offer no cancel`() {
        assertNull(buildContextMenuActions(entry(), canRetranscribe = false).firstOrNull { it == ContextMenuAction.CANCEL })
    }
}
