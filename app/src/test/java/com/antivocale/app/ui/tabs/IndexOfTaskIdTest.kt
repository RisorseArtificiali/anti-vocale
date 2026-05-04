package com.antivocale.app.ui.tabs

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for indexOfTaskId — the flat LazyColumn index lookup
 * used by the notification-to-content highlight feature.
 */
class IndexOfTaskIdTest {

    private fun makeEntry(taskId: String) =
        LogEntry(
            taskId = taskId,
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
        // header=0, vad_advisory=1, "Today" header=2, a=3, b=4, c=5
        assertEquals(3, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(4, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(5, indexOfTaskIdInGroups(groups, "c"))
    }

    @Test
    fun `returns -1 when taskId not found`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a")))
        )
        assertEquals(-1, indexOfTaskIdInGroups(groups, "missing"))
    }

    @Test
    fun `returns -1 for empty groups`() {
        assertEquals(-1, indexOfTaskIdInGroups(emptyList(), "a"))
    }

    @Test
    fun `accounts for multiple group headers`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a"), makeEntry("b"))),
            DateGroup("Yesterday", listOf(makeEntry("c"), makeEntry("d")))
        )
        // header=0, advisory=1, Today header=2, a=3, b=4, Yesterday header=5, c=6, d=7
        assertEquals(3, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(4, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(6, indexOfTaskIdInGroups(groups, "c"))
        assertEquals(7, indexOfTaskIdInGroups(groups, "d"))
    }

    @Test
    fun `finds entry in last group with many groups`() {
        val groups = listOf(
            DateGroup("Group1", listOf(makeEntry("a"))),
            DateGroup("Group2", listOf(makeEntry("b"))),
            DateGroup("Group3", listOf(makeEntry("c"))),
            DateGroup("Group4", listOf(makeEntry("target")))
        )
        // header=0, advisory=1; G1 header=2, a=3; G2 header=4, b=5; G3 header=6, c=7; G4 header=8, target=9
        assertEquals(9, indexOfTaskIdInGroups(groups, "target"))
    }

    @Test
    fun `finds entry in group with single entry`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("only-one")))
        )
        assertEquals(3, indexOfTaskIdInGroups(groups, "only-one"))
    }
}
