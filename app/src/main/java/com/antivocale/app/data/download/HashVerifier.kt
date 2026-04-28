package com.antivocale.app.data.download

import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Verifies file integrity using SHA256 checksums.
 *
 * Used after model downloads to detect corruption before the file
 * reaches the native inference layer, where corrupted ONNX models
 * cause hard-to-debug native crashes.
 */
object HashVerifier {

    private const val TAG = "HashVerifier"
    private const val BUFFER_SIZE = 8192

    /**
     * Computes the SHA256 hex digest of a file.
     *
     * @return lowercase hex string, e.g. "a1b2c3..."
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies a file against an expected SHA256 hash.
     *
     * @param file The file to verify
     * @param expectedHash The expected lowercase hex SHA256 hash
     * @return true if the file matches the expected hash
     */
    fun verify(file: File, expectedHash: String): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: ${file.absolutePath}")
            return false
        }
        val actual = sha256(file)
        val match = actual == expectedHash.lowercase()
        if (match) {
            Log.i(TAG, "SHA256 verified: ${file.name}")
        } else {
            Log.e(TAG, "SHA256 mismatch for ${file.name}: expected=$expectedHash actual=$actual")
        }
        return match
    }

    /**
     * Verifies all files in a directory against expected hashes.
     *
     * @param directory The directory containing model files
     * @param expectedHashes Map from filename to expected SHA256 hash
     * @return List of filenames that failed verification (empty if all pass)
     */
    fun verifyDirectory(directory: File, expectedHashes: Map<String, String>): List<String> {
        val failed = mutableListOf<String>()
        for ((fileName, expectedHash) in expectedHashes) {
            val file = File(directory, fileName)
            if (!verify(file, expectedHash)) {
                failed.add(fileName)
            }
        }
        return failed
    }
}
