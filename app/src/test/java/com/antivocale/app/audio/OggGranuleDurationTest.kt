package com.antivocale.app.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class OggGranuleDurationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds a page with real structure: 27-byte header, lacing table, then
     *  the payload, so the OpusHead magic lands at 27 + segments (28 for the
     *  standard single-segment first page). The old fixture wrote the magic
     *  at a bare 27 and so validated the off-by-one the real files exposed. */
    private fun buildPage(granule: Long, lacing: ByteArray, payload: ByteArray): ByteArray {
        val page = ByteArray(27 + lacing.size + payload.size)
        "OggS".toByteArray().copyInto(page, 0)
        page[4] = 0
        page[5] = 0
        for (i in 0 until 8) {
            page[6 + i] = (granule ushr (i * 8)).toByte()
        }
        page[26] = lacing.size.toByte()
        lacing.copyInto(page, 27)
        payload.copyInto(page, 27 + lacing.size)
        return page
    }

    private fun oggPage(granule: Long, withOpusHead: Boolean = false): ByteArray {
        val payload = if (withOpusHead) "OpusHead".toByteArray() + ByteArray(11) else ByteArray(0)
        val lacing = if (withOpusHead) byteArrayOf(19) else ByteArray(0)
        return buildPage(granule, lacing, payload)
    }

    /** A first page whose packet spans TWO lacing entries pushes the magic to
     *  27 + 2; the gate must follow the lacing table, not a fixed offset. */
    private fun twoSegmentOpusHeadPage(granule: Long): ByteArray =
        buildPage(granule, byteArrayOf(255.toByte(), 46), "OpusHead".toByteArray() + ByteArray(293))

    private fun writeOgg(vararg granules: Long): File {
        val dir = tmp.newFolder()
        val file = File(dir, "test.ogg")
        RandomAccessFile(file, "rw").use { raf ->
            granules.forEachIndexed { index, g ->
                raf.write(oggPage(g, withOpusHead = index == 0))
            }
        }
        return file
    }

    @Test
    fun granuleAt48kHzGivesExactDuration() {
        val file = writeOgg(48_000L * 366)
        val preprocessor = AudioPreprocessor()
        val duration = preprocessor.getOggGranuleDuration(file.absolutePath)
        assertEquals(366.0, duration, 0.01)
    }

    @Test
    fun multiplePagesLastGranuleWins() {
        val file = writeOgg(48_000L * 60, 48_000L * 366)
        val preprocessor = AudioPreprocessor()
        val duration = preprocessor.getOggGranuleDuration(file.absolutePath)
        assertEquals(366.0, duration, 0.01)
    }

    @Test
    fun nonOggFileReturnsZero() {
        val file = File(tmp.newFolder(), "test.wav")
        file.writeBytes(ByteArray(1024))
        val preprocessor = AudioPreprocessor()
        val duration = preprocessor.getOggGranuleDuration(file.absolutePath)
        assertEquals(0.0, duration, 0.001)
    }

    @Test
    fun emptyOrTinyFileReturnsZero() {
        val file = File(tmp.newFolder(), "tiny.ogg")
        file.writeBytes(ByteArray(10))
        val preprocessor = AudioPreprocessor()
        assertEquals(0.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.001)
    }

    @Test
    fun zeroGranuleOnFirstPageDoesNotOverrideLastPage() {
        val file = writeOgg(0L, 48_000L * 120)
        val preprocessor = AudioPreprocessor()
        assertEquals(120.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.01)
    }

    @Test
    fun twoLacingEntriesOnFirstPageStillPassTheOpusGate() {
        val dir = tmp.newFolder()
        val file = File(dir, "two-segments.ogg")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(twoSegmentOpusHeadPage(0L))
            raf.write(oggPage(48_000L * 200))
        }
        val preprocessor = AudioPreprocessor()
        assertEquals(200.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.01)
    }

    /** A structurally valid Ogg first page carrying a NON-Opus payload (a
     *  Vorbis identification header starts with a different magic) must be
     *  rejected by the gate, not just non-Ogg bytes. */
    @Test
    fun vorbisFirstPageIsRejectedByTheOpusGate() {
        val dir = tmp.newFolder()
        val file = File(dir, "vorbis.ogg")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(buildPage(0L, byteArrayOf(30), ByteArray(1) + "vorbis".toByteArray()))
            raf.write(oggPage(44_100L * 100))
        }
        val preprocessor = AudioPreprocessor()
        assertEquals(0.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.001)
    }

    /** A first page with a lacing table longer than the old 64-byte window
     *  (30 entries) still holds a legal OpusHead: the widened window must
     *  accept it instead of falling back to the tag path. */
    @Test
    fun thirtyLacingEntriesOnFirstPageStillPassTheOpusGate() {
        val dir = tmp.newFolder()
        val file = File(dir, "wide-lacing.ogg")
        val lacing = ByteArray(30).also { it[29] = 19 }
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(buildPage(0L, lacing, "OpusHead".toByteArray() + ByteArray(11)))
            raf.write(oggPage(48_000L * 200))
        }
        val preprocessor = AudioPreprocessor()
        assertEquals(200.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.01)
    }

    /** A garbage positive granule (payload false-positive of the capture
     *  pattern, or a corrupt last page) implies an impossible duration; the
     *  sanity clamp must reject it so the file falls back to MediaExtractor
     *  instead of tripping the duration ceilings. */
    @Test
    fun absurdGranuleFailsTheSanityClampAndFallsBack() {
        val dir = tmp.newFolder()
        val file = File(dir, "garbage-granule.ogg")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(oggPage(0L, withOpusHead = true))
            raf.write(oggPage(2_000_000_000_000L))
        }
        val preprocessor = AudioPreprocessor()
        assertEquals(0.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.001)
    }

    /** RFC 7845 4.2: duration is (last granule - pre-skip) / 48000. The
     *  pre-skip lives at OpusHead packet bytes 10-11, little-endian. */
    @Test
    fun preSkipIsSubtractedFromTheGranuleDuration() {
        val dir = tmp.newFolder()
        val file = File(dir, "preskip.ogg")
        val payload = "OpusHead".toByteArray() + ByteArray(11)
        payload[10] = 0x38 // 312 samples = 6.5 ms of pre-skip
        payload[11] = 0x01
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(buildPage(0L, byteArrayOf(19), payload))
            raf.write(oggPage(48_000L * 100 + 312))
        }
        val preprocessor = AudioPreprocessor()
        assertEquals(100.0, preprocessor.getOggGranuleDuration(file.absolutePath), 0.001)
    }
}
