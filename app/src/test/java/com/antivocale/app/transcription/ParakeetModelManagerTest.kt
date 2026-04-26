package com.antivocale.app.transcription

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for ParakeetModelManager model validation.
 *
 * Verifies that model directory validation works correctly regardless
 * of how the files were downloaded (archive extraction vs individual files).
 */
class ParakeetModelManagerTest {

    @Test
    fun `REQUIRED_FILES contains exactly the 4 expected files`() {
        assertEquals(4, ParakeetModelManager.REQUIRED_FILES.size)
        assertTrue(ParakeetModelManager.REQUIRED_FILES.contains("encoder.int8.onnx"))
        assertTrue(ParakeetModelManager.REQUIRED_FILES.contains("decoder.int8.onnx"))
        assertTrue(ParakeetModelManager.REQUIRED_FILES.contains("joiner.int8.onnx"))
        assertTrue(ParakeetModelManager.REQUIRED_FILES.contains("tokens.txt"))
    }

    @Test
    fun `validateModelDirectory returns null for non-existent directory`() {
        val dir = File("/nonexistent/path/model")
        assertNull(ParakeetModelManager.validateModelDirectory(dir))
    }

    @Test
    fun `validateModelDirectory returns null when files are missing`() {
        val dir = createTempDir()
        try {
            // Only create 2 of 4 required files
            File(dir, "encoder.int8.onnx").writeText("fake")
            File(dir, "decoder.int8.onnx").writeText("fake")

            assertNull(ParakeetModelManager.validateModelDirectory(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `validateModelDirectory returns model when all files present`() {
        val dir = createTempDir()
        try {
            ParakeetModelManager.REQUIRED_FILES.forEach { fileName ->
                File(dir, fileName).writeText("x".repeat(100))
            }

            val model = ParakeetModelManager.validateModelDirectory(dir)
            assertNotNull(model)
            assertEquals(dir.absolutePath, model!!.path)
            assertTrue(model.isValid)
            assertEquals(400L, model.sizeBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isValidModelPath returns false for file path`() {
        val file = File.createTempFile("test", ".onnx")
        try {
            assertFalse(ParakeetModelManager.isValidModelPath(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `isValidModelPath returns true for valid model directory`() {
        val dir = createTempDir()
        try {
            ParakeetModelManager.REQUIRED_FILES.forEach { fileName ->
                File(dir, fileName).writeText("model data")
            }

            assertTrue(ParakeetModelManager.isValidModelPath(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PARAKEET_MODEL_DIR constant is parakeet-tdt`() {
        assertEquals("parakeet-tdt", ParakeetModelManager.PARAKEET_MODEL_DIR)
    }

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "parakeet-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
