package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import com.antivocale.app.data.catalog.BundledCatalog
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-885: the missing-files twin of
 * [TranscriptionOrchestratorCorruptModelTest]. A catalog file that VANISHED
 * after a previously successful download (the 1.14.1 Lenovo crash texture:
 * a listed decoder absent at the native open) must surface the typed
 * [TranscriptionException.MissingModelFiles] verdict and self-heal exactly
 * like corruption: the dir is deleted and the Models tab re-offers the
 * download, instead of the pre-885 generic ModelLoadError that wedged the
 * entry until a manual delete.
 *
 * Same harness discipline as the corrupt test: the only mocked hop
 * delegates to a REAL [SherpaBackend.initialize], whose completeness layer
 * fails before any native construction (JVM-safe by construction).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorMissingModelTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

    override fun baseSetUp() {
        super.baseSetUp()
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /**
     * A turbo variant dir with every catalog file EXCEPT the decoder: the
     * dir resolves (name matches a variant dirName) and the encoder/tokens
     * are healthy, so the failure isolates the missing file exactly the way
     * the crash report saw it.
     */
    private fun createTurboDirMissingDecoder(): File {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant("turbo")!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-missing-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f ->
            if (f.name.contains("decoder")) return@forEach // the vanished file
            val file = File(variantDir, f.name)
            if (f.name.endsWith(".onnx")) {
                file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        return variantDir
    }

    private fun createMockContext(): Context =
        mockk<Context>(relaxed = true) {
            every { getSystemService(ActivityManager::class.java) } returns null
            every { filesDir } returns File(System.getProperty("java.io.tmpdir"), "ctx-${System.nanoTime()}").apply {
                mkdirs()
                tempDirs.add(this)
            }
        }

    private fun stubWhisperLoad(savedDir: File) {
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns flowOf(savedDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
    }

    private fun delegateSetActiveBackendToRealBackend() {
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } coAnswers {
            SherpaBackend(BuiltInBackendIds.WHISPER)
                .initialize(context = secondArg(), config = thirdArg())
        }
    }

    private suspend fun runTextRequest(context: Context, scope: kotlinx.coroutines.CoroutineScope): Result<String> =
        orchestrator.processRequest(
            taskId = "missing-model-test",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = scope
        )

    @Test
    fun `missing decoder returns the typed verdict, names the file and deletes the dir`() = runTest {
        val dir = createTurboDirMissingDecoder()
        stubWhisperLoad(dir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(createMockContext(), this)

        val error = result.exceptionOrNull()
        assertTrue("expected MissingModelFiles, got $error",
            error is TranscriptionException.MissingModelFiles)
        // TASK-887: no sibling here; the re-download copy stays.
        assertFalse("no sibling: the flag must stay off",
            (error as TranscriptionException.MissingModelFiles).switchedToSibling)
        assertTrue("the message must name the vanished file",
            error!!.message!!.contains("turbo-decoder.int8.onnx"))
        assertFalse("the message must name ONLY the absent file (the subset, not the whole catalog)",
            error.message!!.contains("turbo-encoder"))
        assertTrue("the message must say missing",
            error.message!!.contains("missing"))
        assertTrue("the message must say re-download",
            error.message!!.contains("re-download"))
        assertFalse("the wedged model dir must be gone", dir.exists())
        coVerify { preferencesManager.clearSherpaModelPath(BuiltInBackendIds.WHISPER) }
    }

    /**
     * The connect-window arm (TASK-885 review round 2): the .size sidecar is
     * written BEFORE the body file is created, so a download that has not
     * started a file yet leaves it ABSENT WITH its sidecar - the downloader's
     * bookkeeping, not a vanish. Healing here would delete the dir while the
     * parallel file streams are still connecting, evaporating the download.
     */
    @Test
    fun `absent file with its sidecar present is a pending download, not a vanish`() = runTest {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant("turbo")!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-connect-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f ->
            val file = File(variantDir, f.name)
            if (f.name.contains("decoder")) {
                // Connect-window shape: body never created, sidecar promised.
                File("${file.path}.size").writeText("999999999")
            } else if (f.name.endsWith(".onnx")) {
                file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        stubWhisperLoad(variantDir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(createMockContext(), this)

        val error = result.exceptionOrNull()
        assertTrue("expected the generic ModelLoadError, got $error",
            error is TranscriptionException.ModelLoadError &&
                error !is TranscriptionException.MissingModelFiles)
        assertTrue("the message must hint at the pending download",
            error!!.message!!.contains("download may still be in progress"))
        assertTrue("the dir must survive (the download still owns it)",
            variantDir.exists())
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }

    /**
     * The download-in-flight arm (TASK-885 review): a file that EXISTS but
     * is shorter than its .size sidecar is a re-download mid-stream, not an
     * absence. The verdict must stay the generic non-healing ModelLoadError
     * so the heal never deletes the dir out from under the running download
     * (the streamed bytes would keep writing to unlinked inodes and the
     * re-download would silently evaporate).
     */
    @Test
    fun `present but sidecar-short file stays generic and deletes nothing`() = runTest {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant("turbo")!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-inflight-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f ->
            val file = File(variantDir, f.name)
            if (f.name.endsWith(".onnx")) {
                if (f.name.contains("decoder")) {
                    // Mid-download shape: present, tiny, sidecar promises more.
                    file.writeBytes(ByteArray(16).also { it[0] = 0x08 })
                    File("${file.path}.size").writeText("999999999")
                } else {
                    file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
                }
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        stubWhisperLoad(variantDir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(createMockContext(), this)

        val error = result.exceptionOrNull()
        assertTrue("expected the generic ModelLoadError, got $error",
            error is TranscriptionException.ModelLoadError &&
                error !is TranscriptionException.MissingModelFiles)
        assertTrue("the message must hint at the in-flight download",
            error!!.message!!.contains("download may still be in progress"))
        assertTrue("the dir must survive (the download is still writing it)",
            variantDir.exists())
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }

    /**
     * The healthy-sibling heal branch for the missing verdict (mirrors the
     * corrupt sibling test): a healthy small variant in the storage root
     * must survive the heal and be re-saved as the active path, so the next
     * request switches to it instead of wedging on the gutted turbo dir.
     */
    @Test
    fun `heal with a healthy sibling variant re-saves the sibling path`() = runTest {
        val context = createMockContext()
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val storageRoot = File(context.filesDir, entry.storageDir ?: BuiltInBackendIds.WHISPER)
        storageRoot.mkdirs()
        tempDirs.add(storageRoot.parentFile)
        val small = entry.variant("small")!!
        val smallDir = File(storageRoot, small.dirName)
        smallDir.mkdirs()
        small.files.forEach { f ->
            val file = File(smallDir, f.name)
            if (f.name.endsWith(".onnx")) {
                file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        // Gutted turbo (decoder absent) saved as the active path, inside the
        // same storage root so the heal's delete targets the real location.
        val turbo = entry.variant("turbo")!!
        val turboDir = File(storageRoot, turbo.dirName)
        turboDir.mkdirs()
        turbo.files.forEach { f ->
            if (f.name.contains("decoder")) return@forEach
            val file = File(turboDir, f.name)
            if (f.name.endsWith(".onnx")) {
                file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        stubWhisperLoad(turboDir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(context, this)

        assertTrue(result.exceptionOrNull() is TranscriptionException.MissingModelFiles)
        // TASK-887: sibling twin of the corrupt arm; the flag rides the
        // re-raised verdict.
        assertTrue("the sibling heal must set the flag",
            (result.exceptionOrNull() as TranscriptionException.MissingModelFiles).switchedToSibling)
        assertEquals("the prefix rides once (the re-raise carries the detail)",
            1, Regex("Model load failed:")
                .findAll(result.exceptionOrNull()!!.message!!).count())
        assertFalse("the gutted turbo dir must be gone", turboDir.exists())
        assertTrue("the healthy sibling dir must stay", smallDir.exists())
        coVerify { preferencesManager.saveSherpaModelPath(BuiltInBackendIds.WHISPER, smallDir.absolutePath) }
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }
}
