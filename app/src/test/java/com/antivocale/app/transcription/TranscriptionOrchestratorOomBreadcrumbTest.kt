package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-679 AC#1/AC#5: an OOM thrown through the orchestrator's catch leaves a
 * persisted breadcrumb naming the resident engines, the RAM readings and the
 * request that tipped the device; the memory-class refusal arms (the pre-flight
 * load refusal, the decode-side refusal) leave the same breadcrumb. The
 * "forced low-memory repro" is the stubbed ActivityManager (100MB free) plus
 * the opt-in memory protection, exactly the shipped refusal path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorOomBreadcrumbTest : TranscriptionOrchestratorTestBase() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backend: TranscriptionBackend
    private lateinit var context: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    private val freeBytes = 500L * 1024 * 1024
    private val totalBytes = 3840L * 1024 * 1024

    override fun baseSetUp() {
        super.baseSetUp()
        backend = stubWhisperBackend()
        stubDefaultWhisperPreferences()
        every { backendManager.activeBackendId } returns MutableStateFlow("whisper")
        context = breadcrumbContext(freeBytes)
    }

    /** Mock Context whose memory reads and preferences are fully controlled. */
    private fun breadcrumbContext(availBytes: Long): Context {
        fakePrefs = FakeSharedPreferences()
        val am = mockk<ActivityManager>()
        every { am.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.availMem = availBytes
            info.totalMem = totalBytes
        }
        return mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns fakePrefs
            every { getSystemService(Context.ACTIVITY_SERVICE) } returns am
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.callProcessRequest(filePath: String): Result<String> =
        orchestrator.processRequest(
            taskId = "oom-1",
            requestType = "audio",
            prompt = "",
            filePath = filePath,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = temporaryFolder.root,
            listener = listener,
            coroutineScope = this,
        )

    private fun persistedLine(): String? =
        fakePrefs.strings["last_breadcrumb"]

    @Test
    fun `an OOM in the decode leaves a breadcrumb naming residents, RAM and the request`() = runTest {
        val audioFile = temporaryFolder.newFile("audio.ogg")
        every { audioPreprocessor.prepareAudioForMediaPipe(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            AudioPreprocessor.PreprocessingResult(
                chunks = listOf(FloatArray(3) { it.toFloat() }),
                sampleRate = 16000,
                totalDurationSeconds = 5.0,
                chunkCount = 1)
        every {
            audioPreprocessor.prepareAudioStream(
                inputPath = any(), maxChunkDurationSeconds = any(), context = any(),
                enableVad = any(), availableRamBytes = any(), maxHeapBytes = any())
        } returns flow {
            emit(AudioPreprocessor.StreamEvent.Header(
                AudioPreprocessor.StreamHeader(sampleRate = 16000, totalDurationSeconds = 5.0, expectedChunkCount = 1)))
            emit(AudioPreprocessor.StreamEvent.Chunk(
                AudioPreprocessor.StreamChunk(
                    samples = FloatArray(3), sampleRate = 16000, chunkIndex = 0, isLast = true)))
        }
        every { audioPreprocessor.getAudioDuration(any()) } returns 1431.0
        // The LLM is resident too: the breadcrumb must name BOTH engines.
        every { llmManager.isReady() } returns true
        coEvery { backend.transcribeAudio(any(), any(), any()) } throws OutOfMemoryError(
            "Failed to allocate a 83886016 byte allocation")

        val result = callProcessRequest(audioFile.absolutePath)

        // TASK-396 contract intact: the OOM surfaces as the typed memory failure.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TranscriptionException.InsufficientMemory)

        val line = persistedLine()
        assertNotNull("the breadcrumb was persisted before the handler bailed", line)
        line!!
        assertTrue(line.startsWith("oom error=OutOfMemoryError"))
        assertTrue("the resident ASR engine is named", line.contains("whisper("))
        assertTrue("the resident LLM engine is named", line.contains("llm("))
        assertTrue("RAM readings are present", line.contains("ram=500/3840MB"))
        assertTrue("the tipping request is named", line.contains("req=whisper"))
        assertTrue(line.contains("dur=1431s"))
    }

    @Test
    fun `a decode-side memory refusal leaves the breadcrumb`() = runTest {
        // TASK-472a arm: protection ON, the whisper family's minimum-chunk
        // baseline (~600MB overhead + headroom) cannot fit 500MB free, so the
        // chunk-cap computation RETURNS the refusal (it does not throw), and
        // the failure fold is where the breadcrumb lands.
        every { preferencesManager.memoryProtection } returns flowOf(true)
        val modelDir = temporaryFolder.newFolder("whisper-model").also {
            java.io.File(it, "encoder.int8.onnx").writeBytes(ByteArray(64 * 1024))
        }
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf(modelDir.absolutePath)
        every { audioPreprocessor.getAudioDuration(any()) } returns 60.0

        val result = callProcessRequest(temporaryFolder.newFile("audio.ogg").absolutePath)

        assertTrue(result.isFailure)
        val line = persistedLine()
        assertNotNull("the refusal arm persisted the breadcrumb", line)
        line!!
        assertTrue(line.startsWith("oom error=InsufficientMemory"))
        assertTrue("the still-resident whisper is named", line.contains("whisper("))
        assertTrue(line.contains("req=whisper"))
        assertTrue(line.contains("ram=500/3840MB"))
    }

    @Test
    fun `a pre-flight load refusal leaves the breadcrumb naming the refused load`() = runTest {
        // No backend active: ensureBackendLoaded takes the load path, and the
        // opt-in pre-flight refuses it (100MB free cannot hold size+headroom).
        every { backendManager.hasActiveBackend() } returns false
        every { backendManager.getActiveBackend() } returns null
        every { backendManager.activeBackendId } returns MutableStateFlow(null)
        context = breadcrumbContext(availBytes = 100L * 1024 * 1024)
        every { preferencesManager.memoryProtection } returns flowOf(true)
        every { preferencesManager.measuredModelMemory } returns flowOf(emptyMap<String, MeasuredModelMemory.Record>())
        val modelDir = temporaryFolder.newFolder("whisper-model").also {
            java.io.File(it, "encoder.int8.onnx").writeBytes(ByteArray(64 * 1024))
        }
        every { preferencesManager.sherpaModelPath("whisper") } returns flowOf(modelDir.absolutePath)
        every { audioPreprocessor.getAudioDuration(any()) } returns 30.0

        val result = callProcessRequest(temporaryFolder.newFile("audio.ogg").absolutePath)

        assertTrue(result.isFailure)
        val line = persistedLine()
        assertNotNull("the load refusal persisted the breadcrumb", line)
        line!!
        assertTrue(line.startsWith("oom error=InsufficientMemory"))
        assertTrue("the refused load is named", line.contains("req=whisper"))
        assertTrue("the refusal's RAM reading is present", line.contains("ram=100/3840MB"))
    }
}

/** In-memory SharedPreferences: the synchronous persist lands here to be read. */
class FakeSharedPreferences : SharedPreferences {
    val strings = mutableMapOf<String, String>()
    val longs = mutableMapOf<String, Long>()

    override fun getAll(): Map<String, *> = strings + longs
    override fun getString(key: String?, defValue: String?): String? = key?.let { strings[it] }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
    override fun getInt(key: String?, defValue: Int): Int = 0
    override fun getLong(key: String?, defValue: Long): Long = key?.let { longs[it] } ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = 0f
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = false
    override fun contains(key: String?): Boolean =
        strings.containsKey(key) || longs.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value == null) prefs.strings.remove(key) else prefs.strings[key] = value
            }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) prefs.longs[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
