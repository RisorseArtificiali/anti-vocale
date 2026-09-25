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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-660 layers 1+2 wiring: a built-in model file corrupted ON DISK must
 * never reach the native recognizer constructor (see
 * [TranscriptionException.CorruptModelFiles] for the abort mechanism). The
 * load returns the typed verdict and the orchestrator deletes the corrupt
 * model dir so the Models tab offers a clean re-download. The negative cases
 * pin the guard: any other load failure deletes nothing.
 *
 * [TranscriptionBackendManager.setActiveBackend] is the only mocked hop and
 * delegates to a REAL [SherpaBackend.initialize], whose validation runs and
 * fails before any native construction (JVM-safe by construction).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorCorruptModelTest : TranscriptionOrchestratorTestBase() {

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
     * A real whisper turbo variant dir (the 1.13.1 Play-crash variant) whose
     * ENCODER is corrupted on disk: over the size floor but garbage bytes, the
     * exact shape the header check must reject. The other files stay healthy
     * so the failure isolates the corrupt file.
     */
    private fun createCorruptTurboDir(): File {
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val variant = entry.variant("turbo")!!
        val parent = File(System.getProperty("java.io.tmpdir"), "whisper-corrupt-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        val variantDir = File(parent, variant.dirName)
        variantDir.mkdirs()
        variant.files.forEach { f ->
            val file = File(variantDir, f.name)
            when {
                f.name.endsWith(".onnx") && f.name.contains("encoder") ->
                    // 2KB of zeros: past the 1KB floor, first byte is not the
                    // ONNX protobuf tag 0x08.
                    file.writeBytes(ByteArray(2048))
                f.name.endsWith(".onnx") ->
                    file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
                else -> file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        return variantDir
    }

    /** OOM pre-flight fails open on a mock Context (see TranscriptionOrchestratorLanguageTest). */
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

    /** Delegates the one mocked hop to a real SherpaBackend so the validation actually runs. */
    private fun delegateSetActiveBackendToRealBackend() {
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } coAnswers {
            SherpaBackend(BuiltInBackendIds.WHISPER)
                .initialize(context = secondArg(), config = thirdArg())
        }
    }

    private suspend fun runTextRequest(context: Context, scope: kotlinx.coroutines.CoroutineScope): Result<String> =
        orchestrator.processRequest(
            taskId = "corrupt-model-test",
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
    fun `corrupt encoder returns the typed verdict, names the file and deletes the dir`() = runTest {
        val dir = createCorruptTurboDir()
        stubWhisperLoad(dir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(createMockContext(), this)

        val error = result.exceptionOrNull()
        assertTrue("expected CorruptModelFiles, got $error",
            error is TranscriptionException.CorruptModelFiles)
        assertTrue("the message must name the failing file",
            error!!.message!!.contains("turbo-encoder.int8.onnx"))
        assertTrue("the message must say corrupted",
            error.message!!.contains("corrupted"))
        assertTrue("the message must say re-download",
            error.message!!.contains("re-download"))
        assertFalse("the corrupt model dir must be gone", dir.exists())
        coVerify { preferencesManager.clearSherpaModelPath(BuiltInBackendIds.WHISPER) }
    }

    /**
     * Review F4: the healthy-SIBLING branch of the heal. The corrupt turbo dir
     * is the SAVED variant; a healthy small variant lives in the manager's
     * storage root (where resolveActiveModelPath scans). The heal must delete
     * only turbo and RE-SAVE the sibling's path, so the next request switches
     * to the healthy model instead of wedging the entry.
     */
    @Test
    fun `heal with a healthy sibling variant re-saves the sibling path`() = runTest {
        val context = createMockContext()
        val entry = BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!
        val storageRoot = File(context.filesDir, entry.storageDir ?: BuiltInBackendIds.WHISPER)
        storageRoot.mkdirs()
        tempDirs.add(storageRoot.parentFile)
        // Healthy sibling: small variant with valid-shaped files.
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
        // Corrupt turbo saved as the active path, in the SAME storage root so
        // the heal's delete targets the installed location.
        val turbo = entry.variant("turbo")!!
        val turboDir = File(storageRoot, turbo.dirName)
        turboDir.mkdirs()
        turbo.files.forEach { f ->
            val file = File(turboDir, f.name)
            if (f.name.endsWith(".onnx")) {
                file.writeBytes(ByteArray(2048).also { it[0] = 0x08 })
                if (f.name.contains("encoder")) file.writeBytes(ByteArray(2048))
            } else {
                file.writeText((1..100).joinToString("\n") { "tok$it $it" })
            }
        }
        stubWhisperLoad(turboDir)
        delegateSetActiveBackendToRealBackend()

        val result = runTextRequest(context, this)

        assertTrue(result.exceptionOrNull() is TranscriptionException.CorruptModelFiles)
        assertFalse("the corrupt turbo dir must be gone", turboDir.exists())
        assertTrue("the healthy sibling dir must stay", smallDir.exists())
        coVerify { preferencesManager.saveSherpaModelPath(BuiltInBackendIds.WHISPER, smallDir.absolutePath) }
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }

    @Test
    fun `a generic ModelLoadError does not delete the dir`() = runTest {
        val dir = createCorruptTurboDir()
        stubWhisperLoad(dir)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns
            Result.failure(TranscriptionException.ModelLoadError("synthetic non-corruption failure"))

        val result = runTextRequest(createMockContext(), this)

        assertTrue(result.isFailure)
        assertTrue(dir.exists())
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }

    @Test
    fun `an OOM-class load failure does not delete the dir`() = runTest {
        val dir = createCorruptTurboDir()
        stubWhisperLoad(dir)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns
            Result.failure(TranscriptionException.InsufficientMemory("avail 1MB"))

        val result = runTextRequest(createMockContext(), this)

        assertTrue(result.isFailure)
        assertTrue(dir.exists())
        coVerify(exactly = 0) { preferencesManager.clearSherpaModelPath(any()) }
    }
}
