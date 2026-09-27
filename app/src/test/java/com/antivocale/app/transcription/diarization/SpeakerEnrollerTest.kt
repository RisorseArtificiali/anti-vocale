package com.antivocale.app.transcription.diarization

import android.net.Uri
import com.antivocale.app.audio.AudioPreprocessor
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 16_000

/**
 * TASK-670 simplify F2: the enrollment pipeline's branch map, driven
 * through the REAL [SpeakerEnroller] with the environment seams faked
 * (SpeakerNamerTest's shape: pin the decisions, not the machinery). The
 * preprocessor is a mockk because the decode is Android-bound; the SAF
 * copy, the metadata probe, the model download, and the titanet embed are
 * recording lambdas.
 */
class SpeakerEnrollerTest {
    /** Review F7: the fixtures' temp roots are removed per run (the /tmp
     *  quota incident class: unbounded test residue killed overnight runs). */
    private val tempRoots = mutableListOf<File>()

    @org.junit.After fun cleanup() {
        tempRoots.forEach { root -> root.deleteRecursively() }
        tempRoots.clear()
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory(
        "speaker-enroller-test").toFile().also { tempRoots.add(it) }


    /** The faked environment around the real pipeline; every knob is one branch. */
    private inner class Pipeline(
        var metadataSeconds: Double? = null,
        var decodedSeconds: Int = 5, // in bounds by default
        var decodeError: Exception? = null,
        var readError: Exception? = null,
        var modelsDownloaded: Boolean = true,
        var embedError: Exception? = null,
        var threads: Int = 2,
    ) {
        val preprocessor = mockk<AudioPreprocessor>()
        val clip: File by lazy { File(tempDir(), "clip.m4a").apply { writeBytes(ByteArray(16)) } }
        var decodeCalls = 0
        var embedCalls = 0
        val seenThreadCounts = mutableListOf<Int>()

        init {
            every {
                preprocessor.prepareAudioForMediaPipe(
                    inputPath = any(),
                    cacheDir = any(),
                    maxChunkDurationSeconds = any(),
                    context = any(),
                    enableVad = any(),
                    vadNumThreads = any(),
                    vadProvider = any(),
                    availableRamBytes = any(),
                    maxHeapBytes = any())
            } answers {
                decodeCalls++
                decodeError?.let { throw it }
                AudioPreprocessor.PreprocessingResult(
                    chunks = listOf(FloatArray(decodedSeconds * RATE)),
                    sampleRate = RATE,
                    totalDurationSeconds = decodedSeconds.toDouble(),
                    chunkCount = 1,
                )
            }
            every { preprocessor.mergeAndResample(any(), any()) } answers {
                FloatArray(decodedSeconds * RATE) to RATE
            }
        }

        fun enroller(store: SpeakerIdentityStore): SpeakerEnroller = SpeakerEnroller(
            preprocessor = preprocessor,
            threadCount = { threads },
            cacheDir = tempDir(),
            readClip = {
                readError?.let { throw it }
                clip
            },
            metadataSeconds = { metadataSeconds },
            ensureModels = {
                if (modelsDownloaded) Result.success(Unit)
                else Result.failure(IllegalStateException("download failed"))
            },
            embed = { _, _, numThreads ->
                embedCalls++
                seenThreadCounts.add(numThreads)
                embedError?.let { throw it }
                floatArrayOf(1f, 0f)
            },
            store = store,
        )
    }

    private fun emptyStore(): SpeakerIdentityStore =
        SpeakerIdentityStore(tempDir())

    // --- enroll: the R4 metadata pre-check ---

    @Test
    fun `a metadata too long clip is rejected before any decode`() = runBlocking {
        val pipeline = Pipeline(metadataSeconds = 90.0)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        // The rejection rides the shipped catch-all, so the surfaced error
        // is DECODE; the pre-check's win is that the decode never runs.
        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.DECODE), outcome)
        assertEquals(0, pipeline.decodeCalls)
        // The cached clip goes away on every exit, rejection included.
        assertFalse(pipeline.clip.exists())
    }

    @Test
    fun `metadata at the plus one tolerance passes the pre-check to the decode`() = runBlocking {
        // MAX + 1.0 s is the container-clock grace; the STRICT > keeps it
        // past the pre-check and leaves the decision to the decoded bounds.
        val pipeline = Pipeline(
            metadataSeconds = SpeakerIdentityStore.MAX_ENROLL_SECONDS + 1.0)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(1, pipeline.decodeCalls)
        assertTrue(outcome is SpeakerEnroller.EnrollOutcome.Pending)
    }

    // --- enroll: the failure branch map ---

    @Test
    fun `a decode failure maps to DECODE`() = runBlocking {
        val pipeline = Pipeline(
            decodeError = AudioPreprocessor.PreprocessingError.ConversionFailed("boom"))
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.DECODE), outcome)
        assertEquals(0, pipeline.embedCalls)
    }

    @Test
    fun `an unreadable picked clip maps to DECODE`() = runBlocking {
        val pipeline = Pipeline(
            readError = IllegalStateException("unreadable enrollment clip"))
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.DECODE), outcome)
    }

    @Test
    fun `a decoded clip under the minimum maps to TOO_SHORT without embedding`() = runBlocking {
        val pipeline = Pipeline(
            decodedSeconds = SpeakerIdentityStore.MIN_ENROLL_SECONDS.toInt() - 1)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.TOO_SHORT), outcome)
        assertEquals(0, pipeline.embedCalls)
    }

    @Test
    fun `a decoded clip over the maximum with no metadata maps to TOO_LONG`() = runBlocking {
        val pipeline = Pipeline(
            decodedSeconds = SpeakerIdentityStore.MAX_ENROLL_SECONDS.toInt() + 1)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.TOO_LONG), outcome)
    }

    @Test
    fun `a failed diarization model download maps to MODEL_DOWNLOAD without embedding`() = runBlocking {
        val pipeline = Pipeline(modelsDownloaded = false)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.MODEL_DOWNLOAD), outcome)
        assertEquals(0, pipeline.embedCalls)
    }

    @Test
    fun `an embedding failure maps to EXTRACT`() = runBlocking {
        val pipeline = Pipeline(embedError = IllegalStateException("native boom"))
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        assertEquals(SpeakerEnroller.EnrollOutcome.Failed(SpeakerEnrollError.EXTRACT), outcome)
    }

    // --- enroll: success parks the pending sample ---

    @Test
    fun `success parks the merged audio, the embedding, and the picked thread count`() = runBlocking {
        val pipeline = Pipeline(decodedSeconds = 5, threads = 3)
        val outcome = pipeline.enroller(emptyStore()).enroll(mockk())

        val pending = outcome as SpeakerEnroller.EnrollOutcome.Pending
        assertEquals(5f, pending.sample.sampleSeconds)
        assertEquals(5 * RATE, pending.sample.samples.size)
        assertEquals(RATE, pending.sample.sampleRate)
        assertTrue(pending.sample.embedding.contentEquals(floatArrayOf(1f, 0f)))
        assertEquals(listOf(3), pipeline.seenThreadCounts)
        assertEquals(1, pipeline.embedCalls)
        assertFalse(pipeline.clip.exists())
    }

    // --- confirm: the store save ---

    @Test
    fun `confirm persists the named identity with its WAV sample`() = runBlocking {
        val store = emptyStore()
        val pipeline = Pipeline(decodedSeconds = 5)
        val pending = pipeline.enroller(store).enroll(mockk())
            as SpeakerEnroller.EnrollOutcome.Pending

        val outcome = pipeline.enroller(store).confirm(pending.sample, "Alice")

        val saved = outcome as SpeakerEnroller.ConfirmOutcome.Saved
        assertEquals("Alice", saved.identity.name)
        assertEquals(listOf("Alice"), store.list().map { it.name })
        assertTrue(store.sampleFile(saved.identity.id)?.isFile == true)
    }

    @Test
    fun `a save failure maps to SAVE`() = runBlocking {
        // A baseDir that is a regular file: mkdirs fails and the WAV write
        // throws, exactly the review R9 branch (extraction HAD succeeded;
        // the user must not be sent to re-pick a clip).
        val blocker = File(tempDir(), "blocker").apply { writeText("not a dir") }
        val store = SpeakerIdentityStore(blocker)
        val pending = PendingSpeakerEnrollment(
            sampleSeconds = 5f,
            embedding = floatArrayOf(1f, 0f),
            samples = FloatArray(5 * RATE),
            sampleRate = RATE,
        )

        val outcome = Pipeline().enroller(store).confirm(pending, "Alice")

        assertEquals(SpeakerEnroller.ConfirmOutcome.Failed(SpeakerEnrollError.SAVE), outcome)
    }
}
