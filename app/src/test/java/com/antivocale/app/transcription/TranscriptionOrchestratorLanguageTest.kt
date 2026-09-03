package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * TASK-434: the wiring of the transcription-language mapping through
 * [TranscriptionOrchestrator.loadCatalogBackend] against the REAL bundled
 * catalog (seeded by the test base): the variant flagged preferUiLanguage
 * (Whisper Small) resolves the untouched "system" default from the app locale,
 * unflagged Whisper variants keep model-side detection, and single-language
 * forcing in SherpaBackend still wins. The pure mapping matrix lives in
 * [TranscriptionLanguagePolicyTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorLanguageTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

    override fun baseSetUp() {
        super.baseSetUp()
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
        // The locale the "system" default follows; deterministic (the JVM default
        // locale varies by machine).
        orchestrator.uiLocaleProvider = { Locale.ITALIAN }
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /**
     * Creates a model dir whose NAME is the catalog variant dir-name (how the
     * orchestrator and SherpaBackend detect the installed variant) carrying the
     * variant's catalog files (.onnx non-empty: the completeness check rejects
     * zero-length files).
     */
    private fun createVariantDir(variantName: String): File {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant(variantName)!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-lang-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f -> File(variantDir, f.name).writeBytes(byteArrayOf(1)) }
        return variantDir
    }

    /** OOM pre-flight fails open on a mock Context (see TranscriptionOrchestratorBackendOverrideTest). */
    private fun createMockContext(): Context =
        mockk<Context>(relaxed = true) {
            every { getSystemService(ActivityManager::class.java) } returns null
            every { filesDir } returns File(System.getProperty("java.io.tmpdir"), "ctx-${System.nanoTime()}").apply {
                mkdirs()
                tempDirs.add(this)
            }
        }

    private fun assertLoadedLanguage(dir: File, preference: String, expected: String) = runTest {
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns flowOf(dir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf(preference)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        orchestrator.processRequest(
            taskId = "test-language-$preference-${dir.name}",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = createMockContext(),
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.WHISPER,
                context = any(),
                config = match {
                    it is BackendConfig.SherpaOnnxConfig && it.language == expected
                }
            )
        }
    }

    @Test
    fun `untouched default follows the app locale on the flagged small variant`() {
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            expected = "it",
        )
    }

    @Test
    fun `untouched default keeps model-side detection on unflagged whisper variants`() {
        assertLoadedLanguage(
            createVariantDir("turbo"),
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            expected = "",
        )
    }

    @Test
    fun `explicit auto keeps model-side detection on the flagged small variant`() {
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_AUTO,
            expected = "",
        )
    }

    @Test
    fun `pinned language passes through on the flagged small variant`() {
        assertLoadedLanguage(createVariantDir("small"), preference = "it", expected = "it")
    }

    @Test
    fun `locale the variant does not support falls back to auto`() {
        orchestrator.uiLocaleProvider = { Locale("xx") }
        assertLoadedLanguage(
            createVariantDir("small"),
            preference = TranscriptionLanguagePolicy.PREF_SYSTEM,
            expected = "",
        )
    }

    /**
     * Matrix 4: forcedLanguage (SherpaBackend, untouched) wins over anything
     * the policy resolves for the single-language Distil-IT variant.
     */
    @Test
    fun `single-language distil variant forces italian regardless of the resolved language`() {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val distil = entry.variant("distil-large-v3-it")!!
        val backend = SherpaBackend(BuiltInBackendIds.WHISPER)
        // Whatever the orchestrator resolved ("" on the system default, a code
        // when pinned): the distil load always forces "it".
        assertEquals("it", backend.forcedLanguage(entry, distil, ""))
        assertEquals("it", backend.forcedLanguage(entry, distil, "de"))
        assertEquals("it", backend.forcedLanguage(entry, distil, "it"))
    }
}
