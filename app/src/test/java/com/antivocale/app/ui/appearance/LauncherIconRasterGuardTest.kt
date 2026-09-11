package com.antivocale.app.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-488: the per-density derei foreground rasters carry no pixel-level
 * guard (Robolectric cannot really decode WebP), so a wrong-density or
 * stale copy-paste during regeneration would ship green. This guard pins
 * what a JVM test CAN verify from the bytes: every expected file exists,
 * parses as WebP, is square, matches its density bucket's pixel size, and
 * no two variants share bytes within a bucket. A regeneration that scales
 * the wrong source or leaves a stale bucket fails here instead of on a
 * user's mid-density phone.
 */
class LauncherIconRasterGuardTest {

    /** Expected square pixel size per density bucket (108dp canvas). */
    private val bucketSizes = mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432)

    /** Coverage derives from the filesystem, not a hand-copied table: a
     *  variant added or dropped by the export script is seen here without
     *  the test's own list drifting. */
    private fun rasterFiles(): List<File> =
        resDir().listFiles { f -> f.name.startsWith("mipmap-") }
            ?.flatMap { dir ->
                dir.listFiles { f -> f.name.startsWith("ic_launcher_fg_derei_") && f.name.endsWith(".webp") }
                    ?.toList() ?: emptyList()
            }
            .orEmpty()

    private fun slugOf(file: File) =
        file.name.removePrefix("ic_launcher_fg_derei_").removeSuffix(".webp")

    private fun densityOf(file: File) = file.parentFile.name.removePrefix("mipmap-")

    private fun resDir(): File {
        val fromCwd = File("app/src/main/res")
        if (fromCwd.isDirectory) return fromCwd
        // Robolectric-layout runs land in app/; hop up to the module root.
        return File("src/main/res")
    }

    /** Lossless WebP (VP8L): RIFF..WEBP, chunk 'VP8L', 0x2f signature,
     *  then 14-bit little-endian width-1 and height-1. */
    private fun webpLosslessSize(bytes: ByteArray): Pair<Int, Int> {
        // 12-byte RIFF header + 4-byte chunk id + 1-byte signature + 4-bit
        // dimension fields needs 25 bytes before any read below.
        assertTrue("not a RIFF/WEBP container", bytes.size >= 25 &&
            String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP")
        val chunk = String(bytes, 12, 4)
        assertTrue("expected a VP8L (lossless) chunk, found $chunk", chunk == "VP8L")
        assertEquals("VP8L signature", 0x2f, bytes[20].toInt() and 0xFF)
        val bits = (bytes[21].toInt() and 0xFF) or
            ((bytes[22].toInt() and 0xFF) shl 8) or
            ((bytes[23].toInt() and 0xFF) shl 16) or
            ((bytes[24].toInt() and 0xFF) shl 24)
        val w = (bits and 0x3FFF) + 1
        val h = ((bits shr 14) and 0x3FFF) + 1
        return w to h
    }

    @Test
    fun everyRasterOnDiskIsSquareAndAtItsBucketSize() {
        val files = rasterFiles()
        assertTrue("no derei rasters found under ${resDir().path}", files.isNotEmpty())
        files.forEach { file ->
            val expected = bucketSizes[densityOf(file)]
            assertTrue("unexpected density bucket ${densityOf(file)} for ${file.name}", expected != null)
            val (w, h) = webpLosslessSize(file.readBytes())
            assertEquals("${file.name} must be square", w, h)
            assertEquals("${file.name} bucket size", expected, w)
        }
        // Every slug must ship in every bucket: 6 variants x 5 densities.
        assertEquals("expected 30 derei rasters (6 variants x 5 densities)", 30, files.size)
    }

    @Test
    fun noTwoVariantsShareBytesWithinADensity() {
        rasterFiles().groupBy { densityOf(it) }.forEach { (density, files) ->
            val hashes = files.map { it.readBytes().contentHashCode() }
            assertEquals(
                "two $density rasters are byte-identical: a regeneration pasted the wrong variant",
                files.size,
                hashes.toSet().size,
            )
        }
    }
}
