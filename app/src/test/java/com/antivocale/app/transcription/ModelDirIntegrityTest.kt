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
 * same condition is now caught (and healable) before any native call.
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
    fun `length-complete but corrupt content fails the pin and heals`() {
        val dir = tmp.newFolder()
        val goodBytes = onnxBytes()
        val file = writeOnnx(dir, "encoder.int8.onnx", goodBytes)
        // Same length, valid header, different bytes: only the hash catches it.
        val corruptBytes = goodBytes.clone().also { it[1000] = (it[1000] + 1).toByte() }
        file.writeBytes(corruptBytes)
        File(dir, "encoder.int8.onnx.size").writeText(goodBytes.size.toString())

        val failures = ModelDirIntegrity.verify(dir, variant(
            CatalogFile("encoder.int8.onnx", sha256 = sha256(goodBytes))))

        assertEquals(listOf("encoder.int8.onnx"), failures.map { it.file.name })
        assertTrue(failures.single().reason.contains("SHA-256"))

        ModelDirIntegrity.removeFailed(failures)
        assertFalse("the corrupt file must be gone", file.exists())
        assertFalse("its sidecar must be gone too", File(dir, "encoder.int8.onnx.size").exists())
    }

    @Test
    fun `structural corruption fails even without pins`() {
        val dir = tmp.newFolder()
        // Valid first byte but under the ONNX floor: a stub, not a graph.
        val file = File(dir, "decoder.int8.onnx").apply {
            writeBytes(ByteArray(512).also { it[0] = 0x08 })
        }

        val failures = ModelDirIntegrity.verify(dir, variant(CatalogFile("decoder.int8.onnx")))

        assertEquals(1, failures.size)
        assertTrue(failures.single().reason.contains("truncated"))

        ModelDirIntegrity.removeFailed(failures)
        assertFalse(file.exists())
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
}
