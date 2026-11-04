package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Unit tests for [SherpaBackend.missingOnnxMetadata] and [containsSubsequence].
 *
 * These are the pre-native validation functions that prevent silent crashes
 * (sherpa-onnx exit(255)) when encoder ONNX metadata is missing.
 *
 * Uses real encoder tail fixtures extracted from production models:
 * - parakeet_stock_tail.bin: last 4KB of csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
 *   encoder (has all 11 metadata props including vocab_size, subsampling, model_type)
 * - smoothquant_broken_tail.bin: last 4KB of pantinor/parakeet-tdt-0.6b-v3-smoothquant
 *   encoder (0 metadata props, the broken export that caused silent crashes)
 */
class OnnxMetadataValidationTest {

    // ---- containsSubsequence edge cases ----

    @Test
    fun `containsSubsequence finds key at start`() {
        val haystack = "vocab_size=8192".toByteArray()
        val needle = "vocab_size".toByteArray()
        assertTrue(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence finds key at end`() {
        val haystack = "padding_stuff_here_vocab_size".toByteArray()
        val needle = "vocab_size".toByteArray()
        assertTrue(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence returns false for absent key`() {
        val haystack = "nothing_relevant_here".toByteArray()
        val needle = "vocab_size".toByteArray()
        assertFalse(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence handles empty needle`() {
        val haystack = "data".toByteArray()
        val needle = ByteArray(0)
        // Empty needle: matches at position 0 (vacuously true)
        assertTrue(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence handles needle longer than haystack`() {
        val haystack = "ab".toByteArray()
        val needle = "abcdef".toByteArray()
        assertFalse(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence handles empty haystack`() {
        val haystack = ByteArray(0)
        val needle = "vocab_size".toByteArray()
        assertFalse(SherpaBackend.containsSubsequence(haystack, needle))
    }

    @Test
    fun `containsSubsequence finds needle in middle`() {
        val haystack = byteArrayOf(1, 2, 3, 4, 5)
        val needle = byteArrayOf(3, 4)
        assertTrue(SherpaBackend.containsSubsequence(haystack, needle))
    }

    // ---- missingOnnxMetadata against real encoder fixtures (production code path) ----

    @Test
    fun `Stock Parakeet encoder has no missing metadata`() {
        val missing = SherpaBackend.missingOnnxMetadata(
            fixtureToFile("parakeet_stock_tail.bin"),
            listOf("vocab_size", "subsampling", "model_type")
        )
        assertTrue("Stock encoder should have all required metadata. Missing: $missing",
            missing.isEmpty())
    }

    @Test
    fun `head-layout encoder metadata is found (canary-1b-v2 bnoordhuis layout)`() {
        // GH #182 device import: the bnoordhuis canary encoder writes its
        // metadata_props near the START of the file (offset ~100), 1.1 GiB
        // from the end; the old tail-only scan rejected a perfectly valid
        // export at import time. The head+tail scan must find it.
        // 64 KiB fixture, 16 KiB scan window: the file exceeds 2x the window,
        // so the split branch (head + tail as separate regions) runs at
        // sibling scale, with the prop at offset 100 only in the head region.
        val file = fixtureToFile("canary_head_metadata.bin")
        val window = 16L * 1024
        val missing = SherpaBackend.missingOnnxMetadata(
            file, listOf("model_type"), maxScanBytes = window
        )
        assertTrue("Head-layout encoder should satisfy model_type via the head scan. Missing: $missing",
            missing.isEmpty())
        val value = SherpaBackend.onnxMetadataValue(
            file, "model_type", maxScanBytes = window
        )
        assertEquals("EncDecMultiTaskModel", value)
    }

    @Test
    fun `broken SmoothQuant encoder reports all metadata missing`() {
        val missing = SherpaBackend.missingOnnxMetadata(
            fixtureToFile("smoothquant_broken_tail.bin"),
            listOf("vocab_size", "subsampling", "model_type")
        )
        assertEquals("Broken SQ encoder should report all 3 keys missing",
            listOf("vocab_size", "subsampling", "model_type"), missing)
    }

    // ---- missingOnnxMetadata file-handling edge cases ----

    @Test
    fun `missingOnnxMetadata returns all keys for missing file`() {
        val missing = SherpaBackend.missingOnnxMetadata(
            Files.createTempFile("nonexistent", ".bin").toFile().apply { delete() },
            listOf("vocab_size")
        )
        assertEquals(listOf("vocab_size"), missing)
    }

    @Test
    fun `missingOnnxMetadata returns all keys for empty file`() {
        val emptyFile = Files.createTempFile("empty", ".bin").toFile()
        val missing = SherpaBackend.missingOnnxMetadata(
            emptyFile,
            listOf("vocab_size")
        )
        assertEquals(listOf("vocab_size"), missing)
    }

    @Test
    fun `missingOnnxMetadata returns only truly-missing keys`() {
        // Has vocab_size but not subsampling
        val file = Files.createTempFile("partial", ".bin").toFile().apply {
            writeText("some_data vocab_size=8192 more_data")
        }
        val missing = SherpaBackend.missingOnnxMetadata(
            file,
            listOf("vocab_size", "subsampling")
        )
        assertEquals("Only subsampling should be missing", listOf("subsampling"), missing)
    }

    // ---- missingOnnxMetadataKeys pure unit tests (no I/O) ----

    @Test
    fun `missingOnnxMetadataKeys returns all keys for empty buffer`() {
        val missing = SherpaBackend.missingOnnxMetadataKeys(
            ByteArray(0),
            listOf("vocab_size")
        )
        assertEquals(listOf("vocab_size"), missing)
    }

    @Test
    fun `missingOnnxMetadataKeys returns only truly-missing keys`() {
        val data = "some_data vocab_size=8192 more_data".toByteArray()
        val missing = SherpaBackend.missingOnnxMetadataKeys(
            data,
            listOf("vocab_size", "subsampling")
        )
        assertEquals("Only subsampling should be missing", listOf("subsampling"), missing)
    }

    // ---- Helper to load test fixtures into a temp File (exercises the real code path) ----

    // ---- onnxMetadataValue: protobuf value parsing after the key ----

    @Test
    fun `onnxMetadataValue reads the real NeMo model_type from the stock fixture`() {
        // Ground truth inspected in the fixture bytes:
        // "model_type" 0x12 0x12 "EncDecRNNTBPEModel"
        val value = SherpaBackend.onnxMetadataValue(fixtureToFile("parakeet_stock_tail.bin"), "model_type")
        assertEquals("EncDecRNNTBPEModel", value)
    }

    @Test
    fun `onnxMetadataValue returns null for a missing key`() {
        val value = SherpaBackend.onnxMetadataValue(fixtureToFile("smoothquant_broken_tail.bin"), "model_type")
        assertEquals(null, value)
    }

    @Test
    fun `onnxMetadataValue parses a synthetic multi-byte varint length`() {
        // key + 0x12 tag + two-byte varint (200) + 200-byte value
        val value = "v".repeat(200)
        val data = "model_type".toByteArray() +
            byteArrayOf(0x12, 0xC8.toByte(), 0x01) + value.toByteArray()
        assertEquals(value, SherpaBackend.onnxMetadataValueBytes(data, "model_type"))
    }

    @Test
    fun `onnxMetadataValue skips a key occurrence with no length-prefixed value after it`() {
        // The key appears as plain text first (no 0x12 tag follows); the parser
        // must keep scanning and find the real entry later in the buffer.
        val data = "junk model_type junk".toByteArray() +
            "model_type".toByteArray() + byteArrayOf(0x12, 0x03) + "abc".toByteArray()
        assertEquals("abc", SherpaBackend.onnxMetadataValueBytes(data, "model_type"))
    }

    private fun fixtureToFile(name: String): java.io.File {
        val classLoader = javaClass.classLoader!!
        val resource = classLoader.getResource(name)
            ?: throw IllegalStateException("Test fixture not found: $name")
        val tmp = Files.createTempFile("fixture", ".bin").toFile()
        tmp.writeBytes(resource.readBytes())
        return tmp
    }
}
