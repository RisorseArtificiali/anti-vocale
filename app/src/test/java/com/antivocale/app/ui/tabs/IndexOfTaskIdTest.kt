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
        assertEquals(2, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(3, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(4, indexOfTaskIdInGroups(groups, "c"))
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
        assertEquals(2, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(3, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(5, indexOfTaskIdInGroups(groups, "c"))
        assertEquals(6, indexOfTaskIdInGroups(groups, "d"))
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
        assertEquals(8, indexOfTaskIdInGroups(groups, "target"))
    }

    @Test
    fun `finds entry in group with single entry`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("only-one")))
        )
        assertEquals(2, indexOfTaskIdInGroups(groups, "only-one"))
    }

    /**
     * TASK-737: sender sub-sections inside a conversation section. The flat
     * index must count each sender header and skip the rows of collapsed
     * senders, while unlabeled rows stay directly under the section header.
     */
    private fun senderEntry(taskId: String, sender: String?) =
        makeEntry(taskId).copy(senderName = sender)

    @Test
    fun `counts sender headers when all senders expanded`() {
        val group = ConversationGroup(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            logs = listOf(
                stamped("a1", at = 300).copy(senderName = "Anna"),
                stamped("u1", at = 100).copy(senderName = null),
                stamped("b1", at = 200).copy(senderName = "Bruno")
            ),
            senderGroups = listOf(
                SenderGroup("Anna", listOf(stamped("a1", at = 300).copy(senderName = "Anna"))),
                SenderGroup("Bruno", listOf(stamped("b1", at = 200).copy(senderName = "Bruno")))
            ),
            unlabeledLogs = listOf(stamped("u1", at = 100).copy(senderName = null))
        )
        // [0] fixed, [1] section header, [2] Anna header, [3] a1,
        // [4] Bruno header, [5] b1, [6] u1
        val groups = listOf(group)
        assertEquals(3, indexOfTaskIdInGroups(groups, "a1"))
        assertEquals(5, indexOfTaskIdInGroups(groups, "b1"))
        assertEquals(6, indexOfTaskIdInGroups(groups, "u1"))
    }

    @Test
    fun `skips rows of collapsed senders`() {
        val group = ConversationGroup(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            logs = listOf(
                senderEntry("a1", "Anna"),
                senderEntry("a2", "Anna"),
                senderEntry("b1", "Bruno")
            ),
            senderGroups = listOf(
                SenderGroup("Anna", listOf(senderEntry("a1", "Anna"), senderEntry("a2", "Anna"))),
                SenderGroup("Bruno", listOf(senderEntry("b1", "Bruno")))
            ),
            unlabeledLogs = emptyList()
        )
        val groups = listOf(group)
        // Anna collapsed: [1] section header, [2] Anna header (rows hidden),
        // [3] Bruno header, [4] b1
        assertEquals(4, indexOfTaskIdInGroups(groups, "b1") { it != "com.whatsapp|Anna" })
        // a target inside a still-collapsed sender is simply not rendered:
        // the honest answer is the index the row WOULD have once expanded
        // ([2] Anna header, [3] a1, [4] a2); the auto-expand path expands
        // the sender before asking.
        assertEquals(3, indexOfTaskIdInGroups(groups, "a1") { true })
        assertEquals(-1, indexOfTaskIdInGroups(groups, "missing") { true })
    }

    @Test
    fun `group without sender groups keeps the legacy math`() {
        val group = ConversationGroup(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            logs = listOf(makeEntry("a"), makeEntry("b")),
            senderGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("a"), makeEntry("b"))
        )
        // [1] section header, [2] a, [3] b regardless of the predicate
        assertEquals(2, indexOfTaskIdInGroups(listOf(group), "a") { false })
        assertEquals(3, indexOfTaskIdInGroups(listOf(group), "b"))
    }

    /**
     * 737 review F1: whichever block holds the NEWEST row is counted (and
     * rendered) first, so a fresh unlabeled share stays above older sender
     * groups. Equal timestamps keep senders first (the messaging default).
     */
    private fun stamped(taskId: String, at: Long) =
        makeEntry(taskId).copy(timestamp = at)

    @Test
    fun `a collapsed section above the target hides its rows from the count`() {
        val whatsapp = ConversationGroup(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            logs = listOf(makeEntry("w1"), makeEntry("w2")),
            senderGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("w1"), makeEntry("w2"))
        )
        val telegram = ConversationGroup(
            packageName = "org.telegram",
            appName = "Telegram",
            logs = listOf(makeEntry("t1")),
            senderGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("t1"))
        )
        val groups = listOf(whatsapp, telegram)
        // WhatsApp collapsed: [1] WhatsApp header, [2] Telegram header, [3] t1.
        // Named arguments on purpose: with two predicates a positional
        // trailing lambda silently binds to the SENDER one.
        assertEquals(3, indexOfTaskIdInGroups(groups, "t1", isSectionExpanded = { it != "com.whatsapp" }))
        // both expanded: [1] WA header, [2..3] rows, [4] TG header, [5] t1
        assertEquals(5, indexOfTaskIdInGroups(groups, "t1"))
    }

    @Test
    fun `a newer unlabeled row is counted before the sender blocks`() {
        val group = ConversationGroup(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            logs = listOf(
                stamped("a1", at = 100),
                stamped("u1", at = 200)
            ),
            senderGroups = listOf(SenderGroup("Anna", listOf(stamped("a1", at = 100)))),
            unlabeledLogs = listOf(stamped("u1", at = 200))
        )
        // [1] section, [2] u1 (newest block first), [3] Anna header, [4] a1
        assertEquals(2, indexOfTaskIdInGroups(listOf(group), "u1"))
        assertEquals(4, indexOfTaskIdInGroups(listOf(group), "a1"))
    }
}
