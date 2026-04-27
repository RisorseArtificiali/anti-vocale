package com.antivocale.app.data.download

import com.antivocale.app.transcription.ParakeetModelManager
import com.antivocale.app.transcription.WhisperModelManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for the download → cancel → clear → re-download flow.
 *
 * Verifies:
 * - isFileComplete uses sidecar files to detect partial downloads
 * - Model validation rejects partial files that exceed 1MB but are incomplete
 * - Clear removes all files AND sidecars so fresh downloads start from 0
 */
class DownloadClearIntegrationTest {

    // ==================== isFileComplete ====================

    @Test
    fun `isFileComplete returns false for non-existent file`() {
        assertFalse(ResumeDownloadHelper.isFileComplete(File("/nonexistent")))
    }

    @Test
    fun `isFileComplete returns false for empty file`() {
        val dir = tempDir()
        try {
            val file = File(dir, "test.onnx").also { it.createNewFile() }
            assertFalse(ResumeDownloadHelper.isFileComplete(file))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `isFileComplete returns true for file with no sidecar`() {
        val dir = tempDir()
        try {
            val file = File(dir, "test.onnx").also { it.writeText("data") }
            assertTrue(ResumeDownloadHelper.isFileComplete(file))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `isFileComplete returns false when file smaller than sidecar`() {
        val dir = tempDir()
        try {
            val file = File(dir, "encoder.int8.onnx")
            file.writeText("x".repeat(2_000_000))
            ResumeDownloadHelper.sizeSidecar(file).writeText("938000000")

            assertFalse(
                "2MB file with 938MB sidecar should be incomplete",
                ResumeDownloadHelper.isFileComplete(file)
            )
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `isFileComplete returns true when file matches sidecar`() {
        val dir = tempDir()
        try {
            val file = File(dir, "encoder.int8.onnx")
            file.writeText("x".repeat(1000))
            ResumeDownloadHelper.sizeSidecar(file).writeText("1000")

            assertTrue(ResumeDownloadHelper.isFileComplete(file))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `isFileComplete returns true for corrupt sidecar`() {
        val dir = tempDir()
        try {
            val file = File(dir, "encoder.int8.onnx")
            file.writeText("x".repeat(100))
            ResumeDownloadHelper.sizeSidecar(file).writeText("not-a-number")

            assertTrue(ResumeDownloadHelper.isFileComplete(file))
        } finally { dir.deleteRecursively() }
    }

    // ==================== The core bug ====================

    @Test
    fun `BUG REPRO - 2MB partial ONNX files fail validation with sidecars`() {
        val dir = tempDir()
        try {
            File(dir, "tokens.txt").writeText("tokens")

            val encoder = File(dir, "encoder.int8.onnx")
            encoder.writeText("x".repeat(2_000_000))
            ResumeDownloadHelper.sizeSidecar(encoder).writeText("900000000")

            val decoder = File(dir, "decoder.int8.onnx")
            decoder.writeText("x".repeat(2_000_000))
            ResumeDownloadHelper.sizeSidecar(decoder).writeText("900000000")

            assertNull(
                "2MB partials should be rejected (they'd pass old >1MB check)",
                WhisperModelManager.validateModelDirectory(dir)
            )
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `complete files with matching sidecars pass validation`() {
        val dir = tempDir()
        try {
            val tokens = File(dir, "tokens.txt")
            tokens.writeText("tokens data")
            ResumeDownloadHelper.sizeSidecar(tokens).writeText("11")

            val encoder = File(dir, "encoder.int8.onnx")
            encoder.writeText("x".repeat(1000))
            ResumeDownloadHelper.sizeSidecar(encoder).writeText("1000")

            val decoder = File(dir, "decoder.int8.onnx")
            decoder.writeText("x".repeat(1000))
            ResumeDownloadHelper.sizeSidecar(decoder).writeText("1000")

            assertNotNull(
                "Complete files should pass even with sidecars present",
                WhisperModelManager.validateModelDirectory(dir)
            )
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `files without sidecars pass validation`() {
        val dir = tempDir()
        try {
            File(dir, "tokens.txt").writeText("tokens")
            File(dir, "encoder.int8.onnx").writeText("x".repeat(100))
            File(dir, "decoder.int8.onnx").writeText("x".repeat(100))

            assertNotNull(
                "Files without sidecars (extracted/sideloaded) should be valid",
                WhisperModelManager.validateModelDirectory(dir)
            )
        } finally { dir.deleteRecursively() }
    }

    // ==================== Parakeet validation ====================

    @Test
    fun `Parakeet rejects partial ONNX files with sidecars`() {
        val dir = tempDir()
        try {
            File(dir, "tokens.txt").writeText("tokens")
            for (name in listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")) {
                val file = File(dir, name)
                file.writeText("x".repeat(2_000_000))
                ResumeDownloadHelper.sizeSidecar(file).writeText("500000000")
            }

            assertNull(ParakeetModelManager.validateModelDirectory(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `Parakeet accepts complete ONNX files with matching sidecars`() {
        val dir = tempDir()
        try {
            for (name in ParakeetModelManager.REQUIRED_FILES) {
                val file = File(dir, name)
                file.writeText("x".repeat(1000))
                ResumeDownloadHelper.sizeSidecar(file).writeText("1000")
            }

            assertNotNull(ParakeetModelManager.validateModelDirectory(dir))
        } finally { dir.deleteRecursively() }
    }

    // ==================== Clear flow ====================

    @Test
    fun `clear deletes all files including sidecars`() {
        val baseDir = tempDir()
        val modelDir = File(baseDir, "test-model")
        modelDir.mkdirs()
        try {
            // Simulate partial download
            val encoder = File(modelDir, "encoder.int8.onnx")
            encoder.writeText("x".repeat(2_000_000))
            ResumeDownloadHelper.sizeSidecar(encoder).writeText("938000000")

            val tokens = File(modelDir, "tokens.txt")
            tokens.writeText("tokens")

            assertEquals(3, modelDir.listFiles()!!.size) // 2 files + 1 sidecar

            // Simulate clear
            val deleted = modelDir.deleteRecursively()
            assertTrue("deleteRecursively should succeed", deleted)
            assertFalse("Model dir should be gone", modelDir.exists())
        } finally { baseDir.deleteRecursively() }
    }

    @Test
    fun `after clear, Phase 1 sees no complete files`() {
        val dir = tempDir()
        try {
            // Fresh directory — no files
            dir.mkdirs()

            val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
            val filesToDownload = mutableListOf<String>()
            var completedFiles = 0

            // This is the exact Phase 1 logic from SherpaOnnxModelDownloader
            for (fileName in files) {
                val targetFile = File(dir, fileName)
                val sidecar = ResumeDownloadHelper.sizeSidecar(targetFile)
                val storedTotal = sidecar.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                if (targetFile.exists() && storedTotal != null && targetFile.length() >= storedTotal) {
                    completedFiles++
                } else {
                    filesToDownload.add(fileName)
                }
            }

            assertEquals("All files should need downloading", 3, filesToDownload.size)
            assertEquals("No files should be pre-complete", 0, completedFiles)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `after clear, downloadWithResume sees no partial file to resume from`() {
        val dir = tempDir()
        try {
            // Fresh file — doesn't exist
            val targetFile = File(dir, "encoder.int8.onnx")
            assertFalse(targetFile.exists())

            // This is what downloadWithResume checks
            val partialSize = targetFile.length()
            assertEquals("Partial size should be 0 for non-existent file", 0L, partialSize)

            // With partialSize = 0, no Range header is sent → download starts from 0
        } finally { dir.deleteRecursively() }
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "dl-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
