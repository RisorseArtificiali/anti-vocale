package com.antivocale.app.data.download

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.SherpaModelDownloader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests proving the sherpa downloader objects are fully catalog-driven:
 * every downloader resolves its entry (and, for multi-variant entries, its
 * per-variant directory) from the bundled catalog, so no download metadata
 * lives in the downloader code anymore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogModelDownloaderTest {

    private fun syntheticGatedEntry() = com.antivocale.app.data.catalog.CatalogEntry(
        id = "test-gated", runtime = "litertlm", modelType = "", family = "",
        display = com.antivocale.app.data.catalog.CatalogDisplay.Literal("Gated Test"),
        storageDir = "models",
        variants = listOf(com.antivocale.app.data.catalog.CatalogVariant(
            name = "v1", dirName = "test-gated-v1", estimatedSizeMB = 1,
            source = com.antivocale.app.data.catalog.CatalogSource(kind = "huggingface", repo = "example/gated"),
            files = listOf(com.antivocale.app.data.catalog.CatalogFile(name = "model.litertlm")),
            requiresAuth = true)))

    @Test
    fun `a gated variant without a token fails fast with the add-token error`() {
        val downloader = CatalogModelDownloader.of(syntheticGatedEntry(), "v1")
        var observed: DownloadState? = null
        val result = kotlinx.coroutines.runBlocking {
            downloader.downloadModel(context, onStateChange = { observed = it }, tokenManager = null)
        }
        assertTrue(result.isFailure)
        // The typed pre-flight error the UI layer will key on (slice 2).
        assertTrue(result.exceptionOrNull() is DownloadException.AuthRequired)
        assertTrue(observed is DownloadState.Error)
        // Not even an empty dir: the pre-flight returns before the delegate
        // creates anything.
        assertFalse(java.io.File(context.filesDir, "models/test-gated-v1").exists())
    }

    @Test
    fun `a blank token is the same fast failure`() {
        val blank = io.mockk.mockk<com.antivocale.app.data.HuggingFaceTokenManager>()
        io.mockk.every { blank.getEffectiveToken() } returns "   "
        val downloader = CatalogModelDownloader.of(syntheticGatedEntry(), "v1")
        val result = kotlinx.coroutines.runBlocking {
            downloader.downloadModel(context, tokenManager = blank)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("requires a HuggingFace account") == true)
    }

    @Test
    fun `litertlm joins the validator completeness set`() {
        val dir = kotlin.io.path.createTempDirectory("litertlm-validator").toFile()
        try {
        // Missing file: invalid.
        assertFalse(CatalogModelValidator.isValidModelDir(dir, listOf("model.litertlm")))
        // Present non-empty, no sidecar (the legacy flat layout): valid.
        java.io.File(dir, "model.litertlm").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(CatalogModelValidator.isValidModelDir(dir, listOf("model.litertlm")))
        // Empty file: invalid.
        java.io.File(dir, "model.litertlm").writeBytes(ByteArray(0))
        assertFalse(CatalogModelValidator.isValidModelDir(dir, listOf("model.litertlm")))
        } finally {
            dir.deleteRecursively()
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BundledCatalog.attach(context)
    }

    @Test
    fun `variant-named downloaders resolve their variant directories from the catalog`() {
        val parakeet = SherpaModelDownloader.of("sherpa-onnx")
        assertEquals("parakeet-tdt-0.6b-v3-smoothquant", parakeet.getModelDirName("smoothquant"))
        assertEquals("parakeet-tdt-0.6b-v3-int8", parakeet.getModelDirName("stock-int8"))
        val whisper = SherpaModelDownloader.of("whisper")
        assertEquals("sherpa-onnx-whisper-turbo", whisper.getModelDirName("turbo"))
        assertEquals("sherpa-onnx-whisper-distil-large-v3-it", whisper.getModelDirName("distil-large-v3-it"))
        val qwen3 = SherpaModelDownloader.of("qwen3-asr")
        assertEquals("sherpa-onnx-qwen3-asr-0.6b-int8", qwen3.getModelDirName("0.6b-int8"))
    }

    @Test
    fun `every catalog variant resolves its own directory name`() {
        for (entryId in listOf("sherpa-onnx", "whisper", "qwen3-asr", "nemotron-streaming", "gigaam")) {
            val entry = BundledCatalog.byId(entryId)!!
            val dirs = entry.variants.map { it.dirName }.toSet()
            val downloader = SherpaModelDownloader.of(entryId)
            entry.variants.forEach { variant ->
                val dir = downloader.getModelDirName(variant.name)
                assertTrue("$entryId variant ${variant.name} dir $dir not in catalog", dir in dirs)
            }
        }
    }

    @Test
    fun `gigaam and nemotron resolve single default variants`() {
        assertEquals(
            BundledCatalog.byId("gigaam")!!.defaultVariant.dirName,
            SherpaModelDownloader.of("gigaam").getModelDirName())
        assertEquals(
            BundledCatalog.byId("nemotron-streaming")!!.defaultVariant.dirName,
            SherpaModelDownloader.of("nemotron-streaming").getModelDirName())
    }

    @Test
    fun `parakeet smoothquant reported downloaded once all files exist`() {
        val downloader = SherpaModelDownloader.of("sherpa-onnx")
        val variantName = "smoothquant"
        val catalogVariant = BundledCatalog.byId("sherpa-onnx")!!
            .variants.first { it.name == variantName }
        val modelDir = File(context.filesDir, "parakeet-tdt/${catalogVariant.dirName}")
        catalogVariant.files.forEach { file ->
            val f = File(modelDir, file.name)
            f.parentFile?.mkdirs()
            f.writeText("mock-model-bytes")
        }
        assertTrue(downloader.isModelDownloaded(context, variantName))
        assertEquals(modelDir.path, downloader.getModelPath(context, variantName))
        assertEquals(catalogVariant.estimatedSizeMB, downloader.getEstimatedSizeMB(variantName))
    }

    @Test
    fun `parakeet smoothquant not downloaded when onnx files are missing`() {
        val downloader = SherpaModelDownloader.of("sherpa-onnx")
        val variantName = "smoothquant"
        val modelDir = File(context.filesDir, "parakeet-tdt/parakeet-tdt-0.6b-v3-smoothquant")
        File(modelDir, "encoder.onnx").apply { parentFile?.mkdirs(); writeText("partial") }
        assertFalse(downloader.isModelDownloaded(context, variantName))
    }

    @Test
    fun `parakeet smoothquant downloaded model can be deleted`() {
        val downloader = SherpaModelDownloader.of("sherpa-onnx")
        val variantName = "smoothquant"
        val catalogVariant = BundledCatalog.byId("sherpa-onnx")!!
            .variants.first { it.name == variantName }
        val modelDir = File(context.filesDir, "parakeet-tdt/${catalogVariant.dirName}")
        catalogVariant.files.forEach { file ->
            val f = File(modelDir, file.name)
            f.parentFile?.mkdirs()
            f.writeText("mock-model-bytes")
        }
        assertTrue(downloader.deleteModel(context, variantName))
        assertFalse(modelDir.exists())
    }

    @Test
    fun `qwen3 requires the tokenizer subdirectory`() {
        val downloader = SherpaModelDownloader.of("qwen3-asr")
        val variantName = "0.6b-int8"
        val catalogVariant = BundledCatalog.byId("qwen3-asr")!!
            .variants.first { it.name == variantName }
        val modelDir = File(context.filesDir, "qwen3-asr/${catalogVariant.dirName}")
        catalogVariant.files.forEach { file ->
            val f = File(modelDir, file.name)
            f.parentFile?.mkdirs()
            f.writeText("mock-model-bytes")
        }
        assertTrue(catalogVariant.files.any { it.name.contains("tokenizer") })
        assertTrue(downloader.isModelDownloaded(context, variantName))
        assertNotNull(downloader.getModelPath(context, variantName))
    }
}