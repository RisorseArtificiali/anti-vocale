package com.antivocale.app.ui.tabs

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for indexOfTaskId — the flat LazyColumn index lookup
 * used by the notification-to-content highlight feature.
 */
class IndexOfTaskIdTest {

    private fun makeEntry(taskId: String, timestamp: Long = System.currentTimeMillis()) =
        LogEntry(
            taskId = taskId,
            timestamp = timestamp,
            type = LogEntry.Type.AUDIO,
            status = LogEntry.Status.SUCCESS
        )

    @Test
    fun `finds entry in single group`() {
        val groups = listOf(
            DateGroup("Today", listOf(
                makeEntry("a"),
                makeEntry("b"),
                makeEntry("c")
            ))
        )
        // Header "Today" = index 0, a = 1, b = 2, c = 3
        assertEquals(1, indexOfTaskId(groups, "a"))
        assertEquals(2, indexOfTaskId(groups, "b"))
        assertEquals(3, indexOfTaskId(groups, "c"))
    }

    @Test
    fun `returns -1 when taskId not found`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a")))
        )
        assertEquals(-1, indexOfTaskId(groups, "missing"))
    }

    @Test
    fun `returns -1 for empty groups`() {
        assertEquals(-1, indexOfTaskId(emptyList(), "a"))
    }

    @Test
    fun `accounts for multiple group headers`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a"), makeEntry("b"))),
            DateGroup("Yesterday", listOf(makeEntry("c"), makeEntry("d")))
        )
        // Today header=0, a=1, b=2, Yesterday header=3, c=4, d=5
        assertEquals(1, indexOfTaskId(groups, "a"))
        assertEquals(2, indexOfTaskId(groups, "b"))
        assertEquals(4, indexOfTaskId(groups, "c"))
        assertEquals(5, indexOfTaskId(groups, "d"))
    }

    @Test
    fun `finds entry in last group with many groups`() {
        val groups = listOf(
            DateGroup("Group1", listOf(makeEntry("a"))),
            DateGroup("Group2", listOf(makeEntry("b"))),
            DateGroup("Group3", listOf(makeEntry("c"))),
            DateGroup("Group4", listOf(makeEntry("target")))
        )
        // Headers: 0, 2, 4, 6; Entries: a=1, b=3, c=5, target=7
        assertEquals(7, indexOfTaskId(groups, "target"))
    }

    @Test
    fun `finds entry in group with single entry`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("only-one")))
        )
        assertEquals(1, indexOfTaskId(groups, "only-one"))
    }
}
