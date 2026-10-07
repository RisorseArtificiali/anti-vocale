package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure decision logic of [VoiceNoteDetector] (TASK-270):
 * path matching, the Opus payload rule, and incremental dedup/watermarking.
 * No Android framework involved.
 */
class VoiceNoteDetectorTest {

    private fun entry(
        id: Long = 1L,
        dateAddedSeconds: Long = 1000L,
        displayName: String = "PTT-20260107-WA0000.opus",
        relativePath: String? = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/",
        path: String? = "/storage/emulated/0/$relativePath$displayName",
        mimeType: String? = null,
        ownerPackage: String? = "com.whatsapp",
    ) = VoiceNoteEntry(
        id = id,
        dateAddedSeconds = dateAddedSeconds,
        displayName = displayName,
        path = path,
        relativePath = relativePath,
        mimeType = mimeType,
        ownerPackage = ownerPackage,
    )

    // --- isWhatsAppVoiceNoteDirectory ---

    @Test
    fun `matches the canonical WhatsApp voice note path`() {
        assertTrue(
            VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/",
            ),
        )
    }

    @Test
    fun `matches the legacy external-storage layout`() {
        assertTrue(
            VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(
                "WhatsApp/Media/WhatsApp Voice Notes/",
            ),
        )
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(
            VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(
                "Android/media/com.whatsapp/WhatsApp/Media/whatsapp voice notes/",
            ),
        )
    }

    @Test
    fun `rejects other WhatsApp media folders`() {
        assertFalse(
            VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
            ),
        )
    }

    @Test
    fun `rejects non-WhatsApp audio locations`() {
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteDirectory("Music/"))
        assertFalse(
            VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(
                "Android/media/org.telegram.messenger/Telegram/",
            ),
        )
    }

    @Test
    fun `rejects a null directory`() {
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(null))
    }

    // --- isWhatsAppVoiceNote ---

    @Test
    fun `accepts a canonical entry with no mime via the opus extension`() {
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNote(entry(mimeType = null)))
    }

    @Test
    fun `accepts audio opus mime`() {
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNote(entry(mimeType = "audio/opus")))
    }

    @Test
    fun `accepts the ogg container mime some indexers report for opus`() {
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNote(entry(mimeType = "audio/ogg")))
    }

    @Test
    fun `accepts a wrong mime when the extension is opus`() {
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNote(entry(mimeType = "application/octet-stream")))
    }

    @Test
    fun `rejects a non-opus file even in the voice note folder`() {
        val e = entry(
            displayName = "AUD-20260107-WA0000.mp4",
            mimeType = "audio/mp4",
        )
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNote(e))
    }

    @Test
    fun `rejects an opus file outside the WhatsApp tree`() {
        val e = entry(
            relativePath = "Music/Recordings/",
            path = "/storage/emulated/0/Music/Recordings/PTT.opus",
        )
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNote(e))
    }

    @Test
    fun `rejects markers carried only by the display name`() {
        // Review F2: a podcast or demo file NAMED after the topic but stored
        // in a foreign directory must not be detected.
        val e = entry(
            displayName = "whatsapp-voice-notes-episode.opus",
            relativePath = "Podcasts/",
            path = "/storage/emulated/0/Podcasts/whatsapp-voice-notes-episode.opus",
        )
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNote(e))
    }

    @Test
    fun `falls back to the absolute path when relative path is null`() {
        val e = entry(
            relativePath = null,
            path = "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/PTT.opus",
        )
        assertTrue(VoiceNoteFilter.isWhatsAppVoiceNote(e))
    }

    @Test
    fun `rejects when both paths are null even with the opus extension`() {
        val e = entry(relativePath = null, path = null)
        assertFalse(VoiceNoteFilter.isWhatsAppVoiceNote(e))
    }

    // --- IncrementalFilter ---

    @Test
    fun `emits only entries newer than the watermark`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        f.absorb(listOf(entry(id = 1, dateAddedSeconds = 100)))
        val fresh = f.filter(
            listOf(
                entry(id = 1, dateAddedSeconds = 100), // stale, re-indexed
                entry(id = 2, dateAddedSeconds = 200), // new
            ),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(listOf(2L), fresh.map { it.id })
    }

    @Test
    fun `absorbed baseline entries never surface on a later filter`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        f.absorb(
            listOf(entry(id = 1, dateAddedSeconds = 100), entry(id = 2, dateAddedSeconds = 200)),
        )
        val fresh = f.filter(
            listOf(entry(id = 1, dateAddedSeconds = 100), entry(id = 2, dateAddedSeconds = 200)),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(emptyList<Long>(), fresh.map { it.id })
    }

    @Test
    fun `emits an unseen id in the same second as the watermark`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        f.absorb(listOf(entry(id = 1, dateAddedSeconds = 100)))
        val fresh = f.filter(
            listOf(entry(id = 2, dateAddedSeconds = 100)),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(listOf(2L), fresh.map { it.id })
    }

    @Test
    fun `does not re-emit a seen id within the watermark second`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        f.absorb(listOf(entry(id = 1, dateAddedSeconds = 100)))
        f.filter(listOf(entry(id = 2, dateAddedSeconds = 100)), selfPackage = null)
        val again = f.filter(listOf(entry(id = 2, dateAddedSeconds = 100)), selfPackage = null)
        assertEquals(emptyList<Long>(), again.map { it.id })
    }

    @Test
    fun `skips entries contributed by the app itself`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        val fresh = f.filter(
            listOf(
                entry(id = 1, ownerPackage = "com.antivocale.app"),
                entry(id = 2, ownerPackage = "com.whatsapp"),
            ),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(listOf(2L), fresh.map { it.id })
    }

    @Test
    fun `handles a burst arriving out of order`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        val fresh = f.filter(
            listOf(
                entry(id = 3, dateAddedSeconds = 300),
                entry(id = 1, dateAddedSeconds = 100),
                entry(id = 2, dateAddedSeconds = 200),
            ),
            selfPackage = null,
        )
        assertEquals(listOf(1L, 2L, 3L), fresh.map { it.id })
    }

    @Test
    fun `advances the watermark so later stale seconds are rejected`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        f.filter(listOf(entry(id = 1, dateAddedSeconds = 500)), selfPackage = null)
        val stale = f.filter(listOf(entry(id = 9, dateAddedSeconds = 100)), selfPackage = null)
        assertEquals(emptyList<Long>(), stale.map { it.id })
    }

    @Test
    fun `a null owner package from a non-Q device is not treated as self`() {
        val f = VoiceNoteFilter.IncrementalFilter()
        val fresh = f.filter(
            listOf(entry(id = 1, ownerPackage = null)),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(listOf(1L), fresh.map { it.id })
    }

    @Test
    fun `self-contributed rows still advance the watermark`() {
        // Review F5: without this, app-owned audio at the newest seconds pins
        // the watermark and every poll re-queries the whole window.
        val f = VoiceNoteFilter.IncrementalFilter()
        f.filter(
            listOf(entry(id = 1, dateAddedSeconds = 900, ownerPackage = "com.antivocale.app")),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(900L, f.watermarkSeconds)
    }

    @Test
    fun `absorbed rejected rows are suppressed when a later index pass fills their metadata`() {
        // Review F8: same id, same second, richer fields after a MediaStore
        // re-scan must not surface as a fresh detection.
        val f = VoiceNoteFilter.IncrementalFilter()
        f.absorb(
            listOf(
                entry(id = 7, dateAddedSeconds = 100, displayName = "", mimeType = null),
            ),
        )
        val fresh = f.filter(
            listOf(
                entry(id = 7, dateAddedSeconds = 100, displayName = "PTT-1.opus", mimeType = "audio/opus"),
            ),
            selfPackage = "com.antivocale.app",
        )
        assertEquals(emptyList<Long>(), fresh.map { it.id })
    }
}
