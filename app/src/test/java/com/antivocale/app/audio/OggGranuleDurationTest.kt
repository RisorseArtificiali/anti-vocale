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

    private fun oggPage(granule: Long): ByteArray {
        val page = ByteArray(27)
        "OggS".toByteArray().copyInto(page, 0)
        page[4] = 0
        page[5] = 0
        for (i in 0 until 8) {
            page[6 + i] = (granule ushr (i * 8)).toByte()
        }
        return page
    }

    private fun writeOgg(vararg granules: Long): File {
        val dir = tmp.newFolder()
        val file = File(dir, "test.ogg")
        RandomAccessFile(file, "rw").use { raf ->
            for (g in granules) {
                raf.write(oggPage(g))
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
}
