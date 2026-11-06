package com.antivocale.app.data

import com.antivocale.app.data.HuggingFaceRepoListing.HfFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-373: JVM tests for the litert-lm HF url importer. The planner is pure;
 * importFromUrl takes its download/freeBytes/token deps as function params so
 * these tests need no network and no device.
 */
class LitertLmUrlImporterTest {

    private val listing = HuggingFaceRepoListing()

    private fun lfs(name: String, size: Long = 2_600_000_000L) =
        HfFile.Lfs(name, name, "a".repeat(64), size)

    // ---- Planner (pure) ----

    @Test(expected = IllegalArgumentException::class)
    fun `planner rejects repo without litertlm files`() {
        LitertLmUrlImporter.planDownload(
            files = listOf(HfFile.Plain("README.md", "README.md", 100L), lfs("model.onnx")),
            repoId = "owner/repo")
    }

    @Test
    fun `planner returns single litertlm file directly`() {
        val plan = LitertLmUrlImporter.planDownload(
            files = listOf(HfFile.Plain("README.md", "README.md", 100L), lfs("model.litertlm")),
            repoId = "owner/repo")
        assertEquals("model.litertlm", plan.single().fileName)
        assertEquals(2_600_000_000L, plan.single().sizeBytes)
    }

    @Test
    fun `planner returns all litertlm files when multiple exist`() {
        val plan = LitertLmUrlImporter.planDownload(
            files = listOf(lfs("e2b.litertlm", 1), lfs("e4b.litertlm", 2)),
            repoId = "owner/repo")
        assertEquals(listOf("e2b.litertlm", "e4b.litertlm"), plan.map { it.fileName })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRepoIdOrThrow rejects non hf url`() {
        LitertLmUrlImporter.parseRepoIdOrThrow("https://example.com/x")
    }

    @Test
    fun `parseRepoIdOrThrow accepts full url or bare owner slash repo`() {
        assertEquals("owner/repo",
            LitertLmUrlImporter.parseRepoIdOrThrow("https://huggingface.co/owner/repo"))
        assertEquals("owner/repo",
            LitertLmUrlImporter.parseRepoIdOrThrow("owner/repo"))
    }

    // ---- Download orchestration (injected deps) ----

    @Test
    fun `download passes auth header for gated repos and resolve url form`() {
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        val fakeModel = File.createTempFile("model", ".litertlm").apply { writeBytes(ByteArray(16)) }
        val importer = LitertLmUrlImporter(listing)
        val result = importer.importFromUrl(
            url = "https://huggingface.co/o/r",
            fileName = "m.litertlm", sizeBytes = 10L,
            modelsDir = File("/models"),
            freeBytes = { 1_000_000L }, token = "tok",
            download = { url, _, _, auth -> capturedUrl = url; capturedAuth = auth;
                Result.success(fakeModel) })
        assertTrue(result.isSuccess)
        assertEquals("Bearer tok", capturedAuth)
        assertTrue(capturedUrl!!.endsWith("/o/r/resolve/main/m.litertlm"))
    }

    @Test
    fun `download omits auth header when token is null`() {
        var capturedAuth: String? = "sentinel"
        val fakeModel = File.createTempFile("model", ".litertlm").apply { writeBytes(ByteArray(16)) }
        val importer = LitertLmUrlImporter(listing)
        importer.importFromUrl(
            url = "https://huggingface.co/o/r",
            fileName = "m.litertlm", sizeBytes = 10L,
            modelsDir = File("/models"),
            freeBytes = { 1_000_000L }, token = null,
            download = { _, _, _, auth -> capturedAuth = auth;
                Result.success(fakeModel) })
        assertEquals(null, capturedAuth)
    }

    @Test
    fun `download refuses when free space below 2x size`() {
        val importer = LitertLmUrlImporter(listing)
        val result = importer.importFromUrl(
            url = "https://huggingface.co/o/r",
            fileName = "m.litertlm", sizeBytes = 100L,
            modelsDir = File("/models"),
            freeBytes = { 150L }, token = null,
            download = { _, _, _, _ -> error("must not be called") })
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("free space"))
    }

    @Test
    fun `download rejects empty result file`() {
        val empty = File.createTempFile("empty-model", ".litertlm").apply { writeBytes(ByteArray(0)) }
        val importer = LitertLmUrlImporter(listing)
        val result = importer.importFromUrl(
            url = "https://huggingface.co/o/r",
            fileName = "m.litertlm", sizeBytes = 10L,
            modelsDir = empty.parentFile,
            freeBytes = { 1_000_000L }, token = null,
            download = { _, _, _, _ -> Result.success(empty) })
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("empty"))
    }

    @Test
    fun `download failure propagates as failure`() {
        val importer = LitertLmUrlImporter(listing)
        val result = importer.importFromUrl(
            url = "https://huggingface.co/o/r",
            fileName = "m.litertlm", sizeBytes = 10L,
            modelsDir = File("/models"),
            freeBytes = { 1_000_000L }, token = null,
            download = { _, _, _, _ -> Result.failure(IllegalStateException("401 unauthorized")) })
        assertTrue(result.isFailure)
        assertEquals("401 unauthorized", result.exceptionOrNull()!!.message)
    }
}
