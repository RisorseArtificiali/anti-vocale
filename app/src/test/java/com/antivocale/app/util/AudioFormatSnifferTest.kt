package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-519 (GH #95): magic-byte format sniffing for content URIs whose
 * extension and MIME both fail. Tests the pure detection function with
 * real header bytes for every supported container.
 */
class AudioFormatSnifferTest {

    @Test
    fun `mp3 with ID3 tag`() {
        val header = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)
        assertEquals("mp3", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `mp3 with MPEG sync word`() {
        // 0xFF 0xFB = MPEG-1 Layer III, 128 kbps
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte())
        assertEquals("mp3", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `ADTS AAC maps to the supported aac extension`() {
        // 0xFF 0xF1 = ADTS (MPEG-4): layer bits 00 are reserved in MPEG
        // audio but are exactly the ADTS signature
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte())
        assertEquals("aac", AudioFormatSniffer.detect(header))
        // 0xFF 0xF9 = the MPEG-2 ADTS variant (ID=1, layer 00)
        val mpeg2 = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x50.toByte(), 0x80.toByte())
        assertEquals("aac", AudioFormatSniffer.detect(mpeg2))
    }

    @Test
    fun `sync word with reserved version bits matches nothing`() {
        // version bits 01 are reserved; layer bits 01 would otherwise pass
        val header = byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0x90.toByte(), 0x00.toByte())
        assertNull(AudioFormatSniffer.detect(header))
    }

    @Test
    fun `m4a with ftyp M4A brand`() {
        // bytes 4-7 = "ftyp", bytes 8-11 = "M4A "
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftypM4A ".toByteArray(Charsets.US_ASCII)
        assertEquals("m4a", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `mp4 with ordinary video brand is not demoted to audio`() {
        // isom/mp42 are the brands ordinary MP4 videos carry; classifying
        // them audio-only (m4a) silently disabled the video branches
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftypisom".toByteArray(Charsets.US_ASCII)
        assertEquals("mp4", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `m4v brand stays a video extension`() {
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftypM4V ".toByteArray(Charsets.US_ASCII)
        assertEquals("mp4", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `3gpp2 brand stays a video extension`() {
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftyp3g2a".toByteArray(Charsets.US_ASCII)
        assertEquals("3g2", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `3gp with ftyp 3gp brand`() {
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftyp3gp4".toByteArray(Charsets.US_ASCII)
        assertEquals("3gp", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `mov with ftyp qt brand`() {
        val header = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte(), 0.toByte()) + "ftypqt  ".toByteArray(Charsets.US_ASCII)
        assertEquals("mov", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `ogg container`() {
        val header = "OggS".toByteArray(Charsets.US_ASCII) + byteArrayOf(0.toByte(), 2.toByte())
        assertEquals("ogg", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `wav RIFF WAVE`() {
        val header = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x24, 0x08, 0x00, 0x00) +
            "WAVE".toByteArray(Charsets.US_ASCII)
        assertEquals("wav", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `flac`() {
        val header = "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        assertEquals("flac", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `amr`() {
        val header = "#!AMR".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x0A.toByte())
        assertEquals("amr", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `webm EBML magic`() {
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte())
        assertEquals("webm", AudioFormatSniffer.detect(header))
    }

    @Test
    fun `unknown bytes return null`() {
        assertNull(AudioFormatSniffer.detect(byteArrayOf(0.toByte(), 1.toByte(), 2.toByte(), 3.toByte())))
        assertNull(AudioFormatSniffer.detect("GIF8".toByteArray(Charsets.US_ASCII)))
        assertNull(AudioFormatSniffer.detect(ByteArray(0)))
        assertNull(AudioFormatSniffer.detect(byteArrayOf(0x50, 0x4B))) // PK (zip), too short
    }
}
