package com.antivocale.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-735: the pure classifier behind the identity listener. The shapes
 * pinned here are the ones the 2026-10-01 TASK-269 spike verified on a live
 * device (WhatsApp 1:1: sender in the title, "🎤 Voice message (m:ss)" in
 * the text) plus the group-safety rules the review added.
 */
class VoiceNoteIdentityExtractorTest {

    private val pkg = "com.whatsapp"

    @Test
    fun `the verified WhatsApp one-to-one shape yields sender and duration`() {
        val note = VoiceNoteIdentityExtractor.fromNotification(
            category = "msg",
            packageName = pkg,
            title = "Chiara",
            text = "🎤 Voice message (0:04)",
            conversationTitle = null,
            messagingPersons = { emptyList() },
            isGroupSummary = false,
            postedAtMs = 1_000L,
        )
        assertEquals("Chiara", note?.sender)
        assertEquals(4L, note?.durationSeconds)
        assertEquals(pkg, note?.packageName)
    }

    @Test
    fun `a per-message Person wins over the title, the group-safe sender`() {
        // In a group the TITLE is the GROUP name; the MessagingStyle Person
        // names the actual speaker.
        val note = VoiceNoteIdentityExtractor.fromNotification(
            category = "msg",
            packageName = pkg,
            title = "Family",
            text = "🎤 Voice message (1:02:03)",
            conversationTitle = "Family",
            messagingPersons = { listOf("Chiara") },
            isGroupSummary = false,
            postedAtMs = 1_000L,
        )
        assertEquals("Chiara", note?.sender)
        assertEquals(3723L, note?.durationSeconds)
    }

    @Test
    fun `a group post without a Person is skipped, not attributed to the group name`() {
        val note = VoiceNoteIdentityExtractor.fromNotification(
            category = "msg",
            packageName = pkg,
            title = "Family",
            text = "🎤 Voice message (0:10)",
            conversationTitle = "Family",
            messagingPersons = { emptyList() },
            isGroupSummary = false,
            postedAtMs = 1_000L,
        )
        assertNull(note)
    }

    @Test
    fun `a Person from an older message never names the newest note`() {
        // Review: only the NEWEST message's Person counts; an older Alice
        // followed by a person-less voice note must not attribute to Alice.
        val note = VoiceNoteIdentityExtractor.fromNotification(
            category = "msg",
            packageName = pkg,
            title = null,
            text = "🎤 Voice message (0:04)",
            conversationTitle = null,
            messagingPersons = { listOf("Alice", null) },
            isGroupSummary = false,
            postedAtMs = 1_000L,
        )
        assertNull(note) // no newest Person, no title: no trustworthy sender
    }

    @Test
    fun `a category-msg notification from a non-messaging package is skipped`() {
        // The privacy contract is a conjunction; Telegram stays out until
        // its voice marker is verified on a device.
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "msg", packageName = "org.telegram.messenger", title = "Chiara",
                text = "🎤 Voice message (0:04)", conversationTitle = null,
                messagingPersons = { listOf("Chiara") }, isGroupSummary = false, postedAtMs = 1L))
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "msg", packageName = "com.crm.inbox", title = "Chiara",
                text = "🎤 Voice message (0:04)", conversationTitle = null,
                messagingPersons = { emptyList() }, isGroupSummary = false, postedAtMs = 1L))
    }

    @Test
    fun `a plain text message is not a voice note`() {
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "msg", packageName = pkg, title = "Chiara",
                text = "a che ora ci vediamo?", conversationTitle = null,
                messagingPersons = { emptyList() }, isGroupSummary = false, postedAtMs = 1L))
    }

    @Test
    fun `group summaries and non-messaging notifications are skipped`() {
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "msg", packageName = pkg, title = "Family",
                text = "🎤 Voice message (0:04)", conversationTitle = "Family",
                messagingPersons = { listOf("Chiara") }, isGroupSummary = true, postedAtMs = 1L))
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "call", packageName = "com.other.app", title = "Chiara",
                text = "🎤 Voice message (0:04)", conversationTitle = null,
                messagingPersons = { emptyList() }, isGroupSummary = false, postedAtMs = 1L))
    }

    @Test
    fun `a marker with no sender in the title is skipped`() {
        assertNull(
            VoiceNoteIdentityExtractor.fromNotification(
                category = "msg", packageName = pkg, title = null,
                text = "🎤 Voice message (0:04)", conversationTitle = null,
                messagingPersons = { emptyList() }, isGroupSummary = false, postedAtMs = 1L))
    }

    @Test
    fun `duration parsing covers minute and hour forms and rejects junk`() {
        assertEquals(4L, VoiceNoteIdentityExtractor.parseDurationSeconds("0:04"))
        assertEquals(3723L, VoiceNoteIdentityExtractor.parseDurationSeconds("1:02:03"))
        assertNull(VoiceNoteIdentityExtractor.parseDurationSeconds("0x04"))
        assertNull(VoiceNoteIdentityExtractor.parseDurationSeconds("1:2:3:4"))
    }
}
