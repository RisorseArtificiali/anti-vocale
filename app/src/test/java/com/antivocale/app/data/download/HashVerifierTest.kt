package com.antivocale.app.data.download

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class HashVerifierTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "hash_test_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `sha256 of known content matches expected hash`() {
        val file = File(tempDir, "test.txt")
        file.writeText("hello world")

        val hash = HashVerifier.sha256(file)
        // SHA256 of "hello world" is well-known
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun `sha256 of empty file returns correct hash`() {
        val file = File(tempDir, "empty.bin")
        file.writeBytes(byteArrayOf())

        val hash = HashVerifier.sha256(file)
        // SHA256 of empty content
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256 returns lowercase hex`() {
        val file = File(tempDir, "lowercase.txt")
        file.writeText("test")

        val hash = HashVerifier.sha256(file)
        assertEquals(hash, hash.lowercase())
        assertFalse(hash.any { it.isUpperCase() })
    }

    @Test
    fun `verify returns true for matching hash`() {
        val file = File(tempDir, "match.bin")
        file.writeText("hello world")
        val expectedHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

        assertTrue(HashVerifier.verify(file, expectedHash))
    }

    @Test
    fun `verify returns false for wrong hash`() {
        val file = File(tempDir, "wrong.bin")
        file.writeText("hello world")

        assertFalse(HashVerifier.verify(file, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun `verify returns false for non-existent file`() {
        val file = File(tempDir, "nonexistent.bin")

        assertFalse(HashVerifier.verify(file, "anyhash"))
    }

    @Test
    fun `verify handles uppercase expected hash`() {
        val file = File(tempDir, "upper.bin")
        file.writeText("hello world")
        val expectedHash = "B94D27B9934D3E08A52E52D7DA7DABFAC484EFE37A5380EE9088F7ACE2EFCDE9"

        assertTrue(HashVerifier.verify(file, expectedHash))
    }

    @Test
    fun `sha256 of binary content is correct`() {
        val file = File(tempDir, "binary.bin")
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()))

        val hash = HashVerifier.sha256(file)
        assertEquals(64, hash.length) // SHA256 = 32 bytes = 64 hex chars
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 handles large file`() {
        val file = File(tempDir, "large.bin")
        // Write 1MB of data
        file.writeBytes(ByteArray(1024 * 1024) { (it % 256).toByte() })

        val hash = HashVerifier.sha256(file)
        assertEquals(64, hash.length)
    }

    @Test
    fun `verifyDirectory returns empty for all matching`() {
        val file1 = File(tempDir, "a.txt").also { it.writeText("aaa") }
        val file2 = File(tempDir, "b.txt").also { it.writeText("bbb") }

        val hashes = mapOf(
            "a.txt" to HashVerifier.sha256(file1),
            "b.txt" to HashVerifier.sha256(file2)
        )

        val failed = HashVerifier.verifyDirectory(tempDir, hashes)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `verifyDirectory returns failed filenames`() {
        File(tempDir, "good.txt").writeText("good content")
        File(tempDir, "bad.txt").writeText("actual content")

        val hashes = mapOf(
            "good.txt" to HashVerifier.sha256(File(tempDir, "good.txt")),
            "bad.txt" to "0000000000000000000000000000000000000000000000000000000000000000"
        )

        val failed = HashVerifier.verifyDirectory(tempDir, hashes)
        assertEquals(listOf("bad.txt"), failed)
    }

    @Test
    fun `verifyDirectory returns missing files as failed`() {
        val hashes = mapOf(
            "missing.txt" to "somehash"
        )

        val failed = HashVerifier.verifyDirectory(tempDir, hashes)
        assertEquals(listOf("missing.txt"), failed)
    }
}
