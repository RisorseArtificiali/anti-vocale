package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-1016 (GH #189): the folder-watch caller label derives ONLY from file
 * names matching a known call-recorder format. The tests pin the public
 * contract stated on the issue: recognized formats get a label, everything
 * else (voice memos, date-only names, anonymous markers, non-audio names)
 * stays unlabeled.
 */
class CallRecordingFilenamesTest {

    @Test
    fun `BCR template with direction sim and phone number`() {
        assertEquals(
            "+15555550123",
            CallRecordingFilenames.callerFromFileName("20230414_215701_in_sim0_+15555550123.m4a"))
    }

    @Test
    fun `BCR template with contact name in underscores`() {
        assertEquals(
            "John Doe",
            CallRecordingFilenames.callerFromFileName("20230414_215701.088-0400_out_John_Doe.m4a"))
    }

    @Test
    fun `BCR date-only template has no caller`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20230414_215701.m4a"))
    }

    @Test
    fun `call-from prefix yields the name before the timestamp`() {
        assertEquals(
            "Mario Rossi",
            CallRecordingFilenames.callerFromFileName("Call from Mario Rossi_20240101_123456.mp3"))
    }

    @Test
    fun `call-with prefix yields the number`() {
        assertEquals(
            "+39 333 1234567",
            CallRecordingFilenames.callerFromFileName("call_with_+39 333 1234567 (2024-01-01).m4a"))
    }

    @Test
    fun `call keyword with phone number and trailing date`() {
        assertEquals(
            "+393331234567",
            CallRecordingFilenames.callerFromFileName("Call Recording_+393331234567_2024-01-01-12-30-45.m4a"))
    }

    @Test
    fun `chiamata keyword with number`() {
        assertEquals(
            "06123456",
            CallRecordingFilenames.callerFromFileName("chiamata_06123456_20240101.amr"))
    }

    @Test
    fun `voice memo record name gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("Record_20240101_123456.m4a"))
    }

    @Test
    fun `date-only name gets no label even with recording keyword`() {
        assertNull(CallRecordingFilenames.callerFromFileName("call_recording_20240101123045.m4a"))
    }

    @Test
    fun `unknown caller marker gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("Call from Unknown (2024-01-01).m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("20230414_215701_in_sim0_Private_Number.m4a"))
    }

    @Test
    fun `number-shaped token without call keyword gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("memo_+393331234567.m4a"))
    }

    @Test
    fun `non-audio name gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20230414_215701_in_sim0_+15555550123.txt"))
    }

    @Test
    fun `no extension gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20230414_215701_in_sim0_+15555550123"))
    }

    @Test
    fun `keyword with separated date time pair gets no label`() {
        // A recorder that names files date_time only: no caller in the name.
        assertNull(CallRecordingFilenames.callerFromFileName("Call Recording_20240101_123456.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("chiamata_20240101_123456.amr"))
    }

    @Test
    fun `BCR head with bare name tail gets no label`() {
        // Without a direction/sim marker or a number-shaped segment, the
        // tail proves nothing about a call (the memo class).
        assertNull(CallRecordingFilenames.callerFromFileName("20240101_123456_groceries.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("20240101_123456_Physics_Lecture.mp4"))
    }

    @Test
    fun `BCR head with a second date run gets no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20230414_215701_20240101.m4a"))
    }

    @Test
    fun `call to prefix is not a recorder format`() {
        assertNull(CallRecordingFilenames.callerFromFileName("Call to Action_2024-01-01.mp3"))
        assertNull(CallRecordingFilenames.callerFromFileName("Call to Arms (live).mp3"))
    }

    @Test
    fun `T-joined BCR head strips and yields the caller`() {
        assertEquals(
            "+15555550123",
            CallRecordingFilenames.callerFromFileName("20230414T215701_in_+15555550123.m4a"))
    }

    @Test
    fun `BCR conference direction with fractional seconds`() {
        assertEquals(
            "+15555550123",
            CallRecordingFilenames.callerFromFileName("20230414_215701.5_conference_+15555550123.m4a"))
    }

    @Test
    fun `no caller id markers get no label`() {
        assertNull(CallRecordingFilenames.callerFromFileName("Call from No Caller ID (2024-01-01).m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("call_with_Withheld_20240101.amr"))
    }

    @Test
    fun `dash-joined head is not a BCR format`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20230414-215701_in_+15555550123.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("20230414-215701_20240101.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("20230414-215701_groceries.m4a"))
    }

    @Test
    fun `tail cut never fires inside a formatted phone number`() {
        assertEquals(
            "+39-0461-123456",
            CallRecordingFilenames.callerFromFileName("call_with_+39-0461-123456.m4a"))
        assertEquals(
            "0461 12345678",
            CallRecordingFilenames.callerFromFileName("call_with_0461 12345678.m4a"))
    }

    @Test
    fun `bare non-E164 number after a BCR marker keeps its label`() {
        assertEquals(
            "15555550123",
            CallRecordingFilenames.callerFromFileName("20230414_215701_in_15555550123.m4a"))
    }

    @Test
    fun `recall is not a call keyword`() {
        assertNull(CallRecordingFilenames.callerFromFileName("recall_note_+15555550123.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("Recall Interview_+15555550123.mp3"))
    }

    @Test
    fun `european day-first date tail is cut from the label`() {
        assertEquals(
            "Anna",
            CallRecordingFilenames.callerFromFileName("Call from Anna 12-03-2024.m4a"))
    }

    @Test
    fun `digit-bearing memo suffix is not a number anchor`() {
        assertNull(CallRecordingFilenames.callerFromFileName("20260101_120101_v2.m4a"))
        assertNull(CallRecordingFilenames.callerFromFileName("20260101_120101_note2.m4a"))
    }

    @Test
    fun `balanced parenthetical survives the date cut`() {
        assertEquals(
            "Anna (mobile)",
            CallRecordingFilenames.callerFromFileName("Call from Anna (mobile) 2024-01-01.m4a"))
    }
}
