package com.antivocale.app.transcription

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.catalog.BundledCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests proving the model managers are fully catalog-driven: storage
 * dirs, known variant dir names, REQUIRED_FILES and (per-variant) file validation
 * all come from the bundled catalog, so no download/validation metadata lives in
 * manager code anymore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelManagerCatalogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BundledCatalog.attach(context)
    }

    private fun installVariant(entryId: String, dirName: String, storageDir: String) {
        val entry = BundledCatalog.byId(entryId)!!
        val variant = entry.variants.first { it.dirName == dirName }
        val modelDir = File(context.filesDir, "$storageDir/$dirName")
        variant.files.forEach { file ->
            val f = File(modelDir, file.name)
            f.parentFile?.mkdirs()
            f.writeText("mock-model-bytes")
        }
    }

    // TASK-932 (GH #176): the fast first-pass resolution behind the
    // two-pass toggle. Streaming preferred, smallest installed otherwise,
    // null when nothing qualifies.

    @Test
    fun `installedFastEntryId prefers the streaming entry over smaller installs`() {
        installVariant(BuiltInBackendIds.WHISPER, "sherpa-onnx-whisper-small", "whisper")
        installVariant(
            BuiltInBackendIds.NEMOTRON,
            "nemotron-3.5-asr-streaming-0.6b-1120ms-int8", "nemotron")
        kotlinx.coroutines.runBlocking {
            assertEquals(
                BuiltInBackendIds.NEMOTRON,
                SherpaModelManager.installedFastEntryId(context, null))
        }
    }

    @Test
    fun `installedFastEntryId falls back to the smallest installed catalog entry`() {
        installVariant(BuiltInBackendIds.PARAKEET, "parakeet-tdt-0.6b-v3-int8", "parakeet-tdt")
        installVariant(BuiltInBackendIds.WHISPER, "sherpa-onnx-whisper-small", "whisper")
        kotlinx.coroutines.runBlocking {
            assertEquals(
                BuiltInBackendIds.WHISPER,
                SherpaModelManager.installedFastEntryId(context, null))
        }
    }

    @Test
    fun `installedFastEntryId is null when nothing is installed`() {
        kotlinx.coroutines.runBlocking {
            assertNull(SherpaModelManager.installedFastEntryId(context, null))
        }
    }

    // TASK-932 review F1 (GH #176): the accurate side (the refinement pin,
    // else the selected backend) is excluded from BOTH arms, so the smallest
    // or streaming candidate can never equal the refinement target.

    @Test
    fun `excluding the smallest installed entry falls through to the next smallest`() {
        // GH #176's shape in miniature: parakeet (640 default-variant size)
        // is the smallest install, so WITHOUT the exclusion the resolver
        // returned the selected backend itself and the gate locked a
        // two-model setup as degenerate.
        installVariant(BuiltInBackendIds.PARAKEET, "parakeet-tdt-0.6b-v3-int8", "parakeet-tdt")
        installVariant(BuiltInBackendIds.QWEN3_ASR, "sherpa-onnx-qwen3-asr-0.6b-int8", "qwen3-asr")
        kotlinx.coroutines.runBlocking {
            assertEquals(
                BuiltInBackendIds.QWEN3_ASR,
                SherpaModelManager.installedFastEntryId(context, BuiltInBackendIds.PARAKEET))
        }
    }

    @Test
    fun `excluding the streaming entry falls through to the smallest other install`() {
        installVariant(BuiltInBackendIds.PARAKEET, "parakeet-tdt-0.6b-v3-int8", "parakeet-tdt")
        installVariant(BuiltInBackendIds.WHISPER, "sherpa-onnx-whisper-small", "whisper")
        installVariant(
            BuiltInBackendIds.NEMOTRON,
            "nemotron-3.5-asr-streaming-0.6b-1120ms-int8", "nemotron")
        kotlinx.coroutines.runBlocking {
            assertEquals(
                BuiltInBackendIds.WHISPER,
                SherpaModelManager.installedFastEntryId(context, BuiltInBackendIds.NEMOTRON))
        }
    }

    @Test
    fun `installedFastEntryId never returns the llm entry`() {
        // The LLM is the one non-audio-recognizer catalog entry; the two-pass
        // fast arm must stay a speech model even when it is the only install.
        installVariant("llm", "gemma-4-e2b", "models")
        kotlinx.coroutines.runBlocking {
            assertNull(SherpaModelManager.installedFastEntryId(context, null))
        }
    }

    @Test
    fun `managers derive storage dirs and valid dir names from the catalog`() {
        fun assertEntry(entryId: String, storageDir: String) {
            val manager = SherpaModelManager.of(entryId)
            val entry = BundledCatalog.byId(entryId)!!
            assertEquals("storageDir for $entryId", File(context.filesDir, storageDir), manager.getModelStorageDir(context))
            assertEquals(
                "validModelDirNames for $entryId",
                entry.variants.map { it.dirName }.toSet(),
                manager.validModelDirNames)
        }
        assertEntry("sherpa-onnx", "parakeet-tdt")
        assertEntry("whisper", "whisper")
        assertEntry("qwen3-asr", "qwen3-asr")
        assertEntry("nemotron-streaming", "nemotron")
        assertEntry("gigaam", "gigaam-v3")
    }

    @Test
    fun `single-variant managers expose catalog REQUIRED_FILES`() {
        assertEquals(
            BundledCatalog.byId("gigaam")!!.defaultVariant.files.map { it.name },
            SherpaModelManager.of("gigaam").REQUIRED_FILES)
        assertEquals(
            BundledCatalog.byId("nemotron-streaming")!!.defaultVariant.files.map { it.name },
            SherpaModelManager.of("nemotron-streaming").REQUIRED_FILES)
    }

    @Test
    fun `parakeet manager validates smoothquant against catalog files`() {
        val manager = SherpaModelManager.of("sherpa-onnx")
        val variantName = "smoothquant"
        val variant = BundledCatalog.byId("sherpa-onnx")!!.variants.first { it.name == variantName }
        installVariant("sherpa-onnx", variant.dirName, "parakeet-tdt")
        val dir = File(context.filesDir, "parakeet-tdt/${variant.dirName}")

        assertTrue(manager.isValidModelPath(dir.absolutePath))
        assertEquals(variantName, manager.detectVariant(variant.dirName))
        assertEquals(variantName, manager.validateModelDirectory(dir)?.variantName)
    }

    @Test
    fun `parakeet manager rejects a dir with missing onnx files`() {
        val manager = SherpaModelManager.of("sherpa-onnx")
        val dir = File(context.filesDir, "parakeet-tdt/parakeet-tdt-0.6b-v3-smoothquant")
        File(dir, "tokens.txt").apply { parentFile?.mkdirs(); writeText("x") }

        assertNull(manager.validateModelDirectory(dir))
        assertNull(manager.detectVariant("parakeet-tdt-0.6b-v3-unknown"))
    }

    @Test
    fun `whisper manager validates per-variant exact files`() {
        val manager = SherpaModelManager.of("whisper")
        val variantName = "turbo"
        val variant = BundledCatalog.byId("whisper")!!.variants.first { it.name == variantName }
        installVariant("whisper", variant.dirName, "whisper")
        val dir = File(context.filesDir, "whisper/${variant.dirName}")

        assertTrue(manager.isValidModelPath(dir.absolutePath))
        val model = manager.validateModelDirectory(dir)
        assertEquals(variantName, model?.variantName)
        val catalogVariant = BundledCatalog.byId("whisper")!!.variants.first { it.dirName == variant.dirName }
        assertEquals(
            File(dir, catalogVariant.files.first { it.name.contains("encoder") }.name).absolutePath,
            model?.encoderPath)
        assertNotNull(model?.decoderPath)
        assertNotNull(model?.tokensPath)
    }

    @Test
    fun `qwen3 manager requires the tokenizer subdirectory from the catalog`() {
        val manager = SherpaModelManager.of("qwen3-asr")
        val variantName = "0.6b-int8"
        val variant = BundledCatalog.byId("qwen3-asr")!!.variants.first { it.name == variantName }
        installVariant("qwen3-asr", variant.dirName, "qwen3-asr")
        val dir = File(context.filesDir, "qwen3-asr/${variant.dirName}")

        assertTrue(manager.isValidModelPath(dir.absolutePath))
        val model = manager.validateModelDirectory(dir)
        assertNotNull(model)
        assertTrue(model?.tokenizerDirPath?.endsWith("tokenizer") == true)
        assertEquals(variantName, manager.detectVariant(variant.dirName))
    }

    @Test
    fun `parakeet resolveActiveModelPath picks the first valid catalog variant`() {
        val manager = SherpaModelManager.of("sherpa-onnx")
        val stock = BundledCatalog.byId("sherpa-onnx")!!.variants.first { it.name == "stock-int8" }
        installVariant("sherpa-onnx", stock.dirName, "parakeet-tdt")
        val expected = File(context.filesDir, "parakeet-tdt/${stock.dirName}").absolutePath
        assertEquals(expected, manager.resolveActiveModelPath(context))
    }

    @Test
    fun `gigaam manager validates against catalog files`() {
        val manager = SherpaModelManager.of("gigaam")
        val variant = BundledCatalog.byId("gigaam")!!.defaultVariant
        installVariant("gigaam", variant.dirName, "gigaam-v3")
        val dir = File(context.filesDir, "gigaam-v3/${variant.dirName}")
        assertTrue(manager.isValidModelPath(dir.absolutePath))
        assertNotNull(manager.validateModelDirectory(dir))
    }
}