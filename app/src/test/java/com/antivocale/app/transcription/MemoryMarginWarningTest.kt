package com.antivocale.app.transcription

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.TranscriptionCalibrator
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.ModelCatalogJson
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TASK-631 part 2: on the DEFAULT path (memory protection off) a tight margin
 * never blocks, but it surfaces ONE dismissable warning notification per model
 * identity per process. MEASURED-BASIS ONLY (review finding): without a
 * measured footprint record (the first load of an identity) there is no
 * warning, so the documented ~1GB overshoot of the disk-size estimate cannot
 * false-alarm healthy devices; with a record, the dedup key is the model
 * identity (swiping the notification away does not re-arm within the process).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryMarginWarningTest {

    private lateinit var context: Context
    private lateinit var preferences: FakePreferencesManager
    private lateinit var orchestrator: TranscriptionOrchestrator

    @Before
    fun setUp() {
        BundledCatalog.seed(
            ModelCatalogJson.parseCatalog(
                java.io.File("src/main/assets/models_catalog.json").readText()))
        context = ApplicationProvider.getApplicationContext()
        preferences = FakePreferencesManager()
        orchestrator = TranscriptionOrchestrator(
            preferences,
            mockk<LogDao>(relaxed = true),
            mockk<TranscriptionCalibrator>(),
            mockk<TranscriptionBackendManager>(relaxed = true),
            mockk<AudioPreprocessor>(relaxed = true),
            staticRegistry(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            ExternalModelStore(preferences, dirExists = { true }),
        )
    }

    private fun modelDir(): File =
        java.nio.file.Files.createTempDirectory("memwarn").toFile().also {
            File(it, "encoder.int8.onnx").writeBytes(ByteArray(64 * 1024))
        }

    private fun seedMeasured(backendId: String, dir: File, maxLoadDeltaBytes: Long) {
        preferences._measuredModelMemory.value =
            preferences._measuredModelMemory.value +
                ("$backendId@cpu@4@${dir.absolutePath}" to
                    MeasuredModelMemory.Record(maxLoadDeltaBytes, 0, 1, 0L))
    }

    private fun posted() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .filter { it.id == TranscriptionOrchestrator.MEMORY_MARGIN_WARNING_ID }

    @Test
    fun `no measured record means no warning even with a tight looking margin`() = runTest {
        val dir = modelDir()
        // On-disk size (64KB) is trivially small; avail is 1KB, so ANY
        // estimate-based branch would fire. The measured-basis rule must not.
        orchestrator.maybeWarnTightMargin(context, "Whisper", "whisper", dir, "cpu", 4, availBytes = 1024L)
        assertEquals(0, posted().size)
    }

    @Test
    fun `a large measured footprint posts the dismissable warning exactly once`() = runTest {
        val dir = modelDir()
        seedMeasured("whisper", dir, maxLoadDeltaBytes = 2L * 1024 * 1024 * 1024)
        repeat(3) {
            orchestrator.maybeWarnTightMargin(context, "Whisper", "whisper", dir, "cpu", 4, availBytes = 1024L)
        }
        val p = posted()
        assertEquals(1, p.size)
        assertTrue(p[0].notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `a small measured footprint posts nothing`() = runTest {
        val dir = modelDir()
        seedMeasured("whisper", dir, maxLoadDeltaBytes = 1024L)
        orchestrator.maybeWarnTightMargin(context, "Whisper", "whisper", dir, "cpu", 4, availBytes = 8L * 1024 * 1024 * 1024)
        assertEquals(0, posted().size)
    }

    @Test
    fun `a different model identity replaces the visible warning, not stacks it`() = runTest {
        val dirA = modelDir()
        val dirB = modelDir()
        seedMeasured("whisper", dirA, maxLoadDeltaBytes = 2L * 1024 * 1024 * 1024)
        seedMeasured("qwen3-asr", dirB, maxLoadDeltaBytes = 2L * 1024 * 1024 * 1024)

        orchestrator.maybeWarnTightMargin(context, "Whisper", "whisper", dirA, "cpu", 4, availBytes = 1024L)
        orchestrator.maybeWarnTightMargin(context, "Qwen3", "qwen3-asr", dirB, "cpu", 4, availBytes = 1024L)

        // One fixed slot (id 1006): the newest tight model wins the surface,
        // and re-visiting the first identity stays deduped (no third post).
        val p = posted()
        assertEquals(1, p.size)
        val text = p[0].notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("expected the newest model in the body, got: $text", text.contains("Qwen3"))
        orchestrator.maybeWarnTightMargin(context, "Whisper", "whisper", dirA, "cpu", 4, availBytes = 1024L)
        assertEquals(1, posted().size)
    }
}
