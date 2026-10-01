package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.CatalogFile
import com.antivocale.app.data.catalog.CatalogVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * TASK-479 / GH #88: the content layer of load-time model validation. The
 * vitals crash was a length-complete but content-corrupt encoder whose bytes
 * reached the native loader and aborted the process; these tests pin that the
 * same condition is now caught before any native call. The heal is the
 * orchestrator's directory delete (catalog models only); nothing here
 * exercises a heal.
 */
class ModelDirIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A valid 2KB ONNX-looking file: 0x08 first byte, over the floor. */
    private fun writeOnnx(dir: File, name: String, bytes: ByteArray = onnxBytes()): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun onnxBytes(): ByteArray =
        ByteArray(2048).also { it[0] = 0x08 }

    private fun variant(vararg files: CatalogFile) = CatalogVariant(
        name = "test", dirName = "test", estimatedSizeMB = 1,
        source = com.antivocale.app.data.catalog.CatalogSource(kind = "huggingface", repo = "org/x"),
        files = files.toList(),
    )

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Same length, valid header, different bytes: only the hash catches it. */
    private fun corrupt(b: ByteArray): ByteArray =
        b.clone().also { it[1000] = (it[1000] + 1).toByte() }

    @Test
    fun `healthy dir with matching pin passes`() {
        val dir = tmp.newFolder()
        val bytes = onnxBytes()
        val file = writeOnnx(dir, "encoder.int8.onnx", bytes)
        File(dir, "encoder.int8.onnx.size").writeText(bytes.size.toString())

        val failures = ModelDirIntegrity.verify(dir, variant(
            CatalogFile("encoder.int8.onnx", sha256 = sha256(bytes))))

        assertTrue("expected no failures, got $failures", failures.isEmpty())
    }

    @Test
    fun `length-complete but corrupt content fails the pin`() {
        val dir = tmp.newFolder()
        val goodBytes = onnxBytes()
        val file = writeOnnx(dir, "encoder.int8.onnx", goodBytes)
        // Same length, valid header, different bytes: only the hash catches it.
        val corruptBytes = corrupt(goodBytes)
        file.writeBytes(corruptBytes)
        File(dir, "encoder.int8.onnx.size").writeText(goodBytes.size.toString())

        val failures = ModelDirIntegrity.verify(dir, variant(
            CatalogFile("encoder.int8.onnx", sha256 = sha256(goodBytes))))

        assertEquals(listOf("encoder.int8.onnx"), failures.map { it.file.name })
        assertTrue(failures.single().reason.contains("SHA-256"))
    }

    @Test
    fun `structural corruption fails even without pins`() {
        val dir = tmp.newFolder()
        // Valid first byte but under the ONNX floor: a stub, not a graph.
        File(dir, "decoder.int8.onnx").apply {
            writeBytes(ByteArray(512).also { it[0] = 0x08 })
        }

        val failures = ModelDirIntegrity.verify(dir, variant(CatalogFile("decoder.int8.onnx")))

        assertEquals(1, failures.size)
        assertTrue(failures.single().reason.contains("truncated"))
    }

    @Test
    fun `unpinned content corruption without structural tells passes silently - the pin is the cure`() {
        val dir = tmp.newFolder()
        val bytes = onnxBytes()
        writeOnnx(dir, "encoder.int8.onnx", bytes.also { it[1000] = 0x7f })

        // No pin: structural checks see a plausible ONNX. This documents the
        // boundary honestly - same-length bit rot is only catchable by hash,
        // which is why the repos we publish get pinned (TASK-479c).
        val failures = ModelDirIntegrity.verify(dir, variant(CatalogFile("encoder.int8.onnx")))

        assertTrue(failures.isEmpty())
    }

    /**
     * TASK-482: the reload cache's conscious boundary, as a pin. A warm cache
     * answers by (path, length, lastModified), so corruption that preserves
     * all three after one clean verify is invisible until the process
     * restarts; the fresh-cache case right after proves the pin itself still
     * catches those bytes, and a stat change (any rewrite/heal/re-download)
     * re-verifies even on the warm cache.
     */
    @Test
    fun `cached verdicts survive stat-preserving bit rot but invalidate on any stat change`() {
        val dir = tmp.newFolder()
        val goodBytes = onnxBytes()
        val file = writeOnnx(dir, "encoder.int8.onnx", goodBytes)
        val pins = variant(CatalogFile("encoder.int8.onnx", sha256 = sha256(goodBytes)))
        val cache = ModelDirIntegrity.PinVerdictCache()

        assertTrue(ModelDirIntegrity.verify(dir, pins, pinCache = cache).isEmpty())

        // Warm cache, unchanged stats: the cached verdict answers (boundary).
        val mtime = file.lastModified()
        file.writeBytes(corrupt(goodBytes))
        file.setLastModified(mtime)
        assertTrue(
            "warm cache must answer without re-hashing (documented boundary)",
            ModelDirIntegrity.verify(dir, pins, pinCache = cache).isEmpty())

        // Same bytes, fresh cache: the pin catches them.
        assertEquals(
            1,
            ModelDirIntegrity.verify(dir, pins, pinCache = ModelDirIntegrity.PinVerdictCache()).size)

        // Any stat change re-verifies even on the warm cache.
        file.setLastModified(mtime + 1_000)
        val failures = ModelDirIntegrity.verify(dir, pins, pinCache = cache)
        assertEquals(listOf("encoder.int8.onnx"), failures.map { it.file.name })
    }

    /**
     * TASK-482 review: a transient listing failure must read as UNREADABLE,
     * not corruption - at the load gate a genuinely empty dir is unreachable
     * (the completeness checks fail first), so a null listFiles (EIO on
     * sdcardfs/FUSE, fd exhaustion) mistyped as "corrupt" would make the
     * heal delete a healthy model directory. Simulated with a chmod-000 dir.
     */
    @Test
    fun `an unreadable directory is a read failure, not a corruption verdict`() {
        val dir = tmp.newFolder()
        java.nio.file.Files.setPosixFilePermissions(
            dir.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("---------"),
        )
        try {
            val verdict = ModelDirIntegrity.split(
                ModelDirIntegrity.verify(dir, variant(CatalogFile("encoder.int8.onnx"))))
            assertTrue(
                "expected an unreadable finding, got ${verdict.corrupt}",
                verdict.corrupt.isEmpty())
            assertEquals(1, verdict.unreadable.size)
        } finally {
            java.nio.file.Files.setPosixFilePermissions(
                dir.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
            )
        }
    }

    /**
     * TASK-482 review round: a DIFFERENT pin against unchanged stats must
     * re-hash - a variant update or re-import rewrites the pin, and the warm
     * verdict is only good for the pin it verified.
     */
    @Test
    fun `a changed pin re-verifies even on the warm cache`() {
        val dir = tmp.newFolder()
        val goodBytes = onnxBytes()
        writeOnnx(dir, "encoder.int8.onnx", goodBytes)
        val cache = ModelDirIntegrity.PinVerdictCache()
        val goodPin = variant(CatalogFile("encoder.int8.onnx", sha256 = sha256(goodBytes)))
        val otherPin = variant(CatalogFile("encoder.int8.onnx", sha256 = sha256(corrupt(goodBytes))))

        assertTrue(ModelDirIntegrity.verify(dir, goodPin, pinCache = cache).isEmpty())
        assertEquals(1, ModelDirIntegrity.verify(dir, otherPin, pinCache = cache).size)
    }

    /**
     * TASK-482: the external backend's pin shape (record.files -> name to
     * sha) through the shared pass: only a wrong pin fails, a missing file is
     * the completeness layer's business, an unpinned name is not checked.
     */
    @Test
    fun `verifyPins reports exactly the mismatching pin`() {
        val dir = tmp.newFolder()
        // Distinct bytes per file: onnxBytes() alone is deterministic, so two
        // files written from it share one hash and a "wrong" pin built from
        // it would accidentally be right.
        val encoderBytes = onnxBytes().also { it[1000] = 0x11 }
        val decoderBytes = onnxBytes().also { it[1000] = 0x22 }
        writeOnnx(dir, "encoder.int8.onnx", encoderBytes)
        writeOnnx(dir, "decoder.int8.onnx", decoderBytes)

        val failures = ModelDirIntegrity.verifyPins(
            dir,
            mapOf(
                "encoder.int8.onnx" to sha256(onnxBytes().also { it[1000] = 0x33 }), // wrong pin
                "decoder.int8.onnx" to sha256(decoderBytes),                          // correct pin
                "joiner.int8.onnx" to "00",                                          // not on disk
            ),
            ModelDirIntegrity.PinVerdictCache(),
        )

        assertEquals(listOf("encoder.int8.onnx"), failures.map { it.file.name })
        assertTrue(failures.single().reason.contains("SHA-256"))
    }
}
