package com.antivocale.app.data.download

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TASK-305: structural post-download validation catches truncated/corrupt
 * downloads before native graph construction.
 */
class DownloadedModelIntegrityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun onnx(name: String, size: Int = 8192, validHeader: Boolean = true): File =
        File(tmp.newFolder(), name).apply {
            writeBytes(ByteArray(size) { if (it == 0 && validHeader) 0x08 else 0x00 })
        }

    @Test
    fun `healthy model dir passes`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx").copyTo(File(dir, "encoder.int8.onnx"))
        onnx("decoder.onnx").copyTo(File(dir, "decoder.onnx"))
        File(dir, "tokens.txt").writeText((1..100).joinToString("\n") { "tok$it $it" })

        assertTrue(DownloadedModelIntegrity.validate(dir).isEmpty())
    }

    @Test
    fun `truncated onnx is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx", size = 100).copyTo(File(dir, "encoder.int8.onnx"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("small"))
    }

    @Test
    fun `empty onnx file is flagged by the floor`() {
        // TASK-660: the zero-byte file is the degenerate truncation; the same
        // size floor must catch it before any recognizer construction.
        val dir = tmp.newFolder()
        File(dir, "encoder.int8.onnx").writeBytes(ByteArray(0))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("small"))
    }

    @Test
    fun `non-onnx payload in an onnx file is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx", validHeader = false).copyTo(File(dir, "encoder.int8.onnx"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("ONNX header"))
    }

    @Test
    fun `stub token file is flagged`() {
        val dir = tmp.newFolder()
        onnx("encoder.int8.onnx").copyTo(File(dir, "encoder.int8.onnx"))
        File(dir, "tokens.txt").writeText("x")

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("token"))
    }

    @Test
    fun `missing directory and empty directory are flagged`() {
        assertEquals(1, DownloadedModelIntegrity.validate(File("/nonexistent")).size)
        assertEquals(1, DownloadedModelIntegrity.validate(tmp.newFolder()).size)
    }

    /** An .ort fixture: 4-byte root offset + "ORTM" identifier + filler. */
    private fun ort(name: String, size: Int = 8192, identifier: Boolean = true, onnxFirstByte: Boolean = false): File =
        File(tmp.newFolder(), name).apply {
            val head = ByteArray(if (onnxFirstByte) 8 else 8) {
                when (it) {
                    0 -> if (onnxFirstByte) 0x08 else 0x14
                    in 4..7 -> if (identifier) "ORTM"[it - 4].code.toByte() else 0x00
                    else -> 0x00
                }
            }
            writeBytes(head + ByteArray(size - head.size))
        }

    @Test
    fun `valid ort file passes the identifier check`() {
        // The moonshine v2 shape (GH #89): a finding here would make the
        // downloader deleteRecursively() the model dir on every clean
        // download, an install loop (review round: this arm had zero tests).
        val dir = tmp.newFolder()
        ort("encoder_model.ort").copyTo(File(dir, "encoder_model.ort"))
        ort("decoder_model_merged.ort").copyTo(File(dir, "decoder_model_merged.ort"))
        File(dir, "tokens.txt").writeText((1..100).joinToString("\n") { "tok$it $it" })

        assertTrue(DownloadedModelIntegrity.validate(dir).isEmpty())
    }

    @Test
    fun `onnx protobuf bytes under an ort name are flagged`() {
        val dir = tmp.newFolder()
        ort("encoder_model.ort", identifier = false, onnxFirstByte = true)
            .copyTo(File(dir, "encoder_model.ort"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains(".ort name"))
    }

    @Test
    fun `identifier-less non-onnx ort is left alone`() {
        // Neither ORTM nor ONNX protobuf: not our failure to diagnose.
        val dir = tmp.newFolder()
        ort("encoder_model.ort", identifier = false).copyTo(File(dir, "encoder_model.ort"))

        assertTrue(DownloadedModelIntegrity.validate(dir).isEmpty())
    }

    @Test
    fun `truncated ort is flagged by the floor`() {
        val dir = tmp.newFolder()
        ort("encoder_model.ort", size = 100).copyTo(File(dir, "encoder_model.ort"))

        val findings = DownloadedModelIntegrity.validate(dir)
        assertEquals(1, findings.size)
        assertTrue(findings[0].reason.contains("small"))
    }
}
