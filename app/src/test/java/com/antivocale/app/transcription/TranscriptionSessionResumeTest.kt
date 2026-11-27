package com.antivocale.app.transcription

import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.local.SessionChunkEntity
import com.antivocale.app.data.local.SessionDao
import com.antivocale.app.data.local.TranscriptionSessionEntity
import com.antivocale.app.service.TranscriptionListener
import com.antivocale.app.ui.viewmodel.LogEntry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

/**
 * TASK-1037 (GH #190) phase 3: the checkpoint-and-kill-recovery floor. The
 * plan's end-state test: with a fake backend and a fake session store, a
 * stint interrupted mid-stream (chunks checkpointed, session parked) resumed
 * must deliver text and cues IDENTICAL to an uninterrupted run of the same
 * audio, with the decode actually restarting at the store's contiguous
 * prefix end.
 *
 * The kill is simulated through the plan's own surfaces: the cooperative
 * pause sentinel parks the stint at a chunk boundary (the real pause path),
 * and the park state is rewritten to PAUSED_SYSTEM, exactly what the
 * cold-start recovery leaves behind after a process death. The resume path
 * keys on the session row's existence, not the pause flavor, so this is the
 * kill floor end to end without needing a real process death. The recovery
 * pass's park side has its own Robolectric pin (SuspendedRunRecoveryTest);
 * the device proof (am kill mid-run, resume, compare) rides the SPI ops.
 *
 * The fake session dao is hand-written (the interface's walk helpers run
 * over the maps); the fake audioPreprocessor stream honors startOffsetSamples
 * exactly like phase 1's decode-and-discard (whole chunks before the offset
 * are dropped), the contract the real decoder satisfies by construction.
 */
class TranscriptionSessionResumeTest : TranscriptionOrchestratorTestBase() {

    /** In-memory SessionDao: the interface's walk helpers run over the maps. */
    private class FakeSessionDao : SessionDao {
        val sessions = mutableMapOf<String, TranscriptionSessionEntity>()
        val chunks = mutableMapOf<String, MutableList<SessionChunkEntity>>()

        override suspend fun upsertSession(session: TranscriptionSessionEntity) {
            sessions[session.taskId] = session
        }

        override suspend fun getSession(taskId: String): TranscriptionSessionEntity? =
            sessions[taskId]

        override suspend fun getActiveSessions(): List<TranscriptionSessionEntity> =
            sessions.values.filter { it.state in setOf("ACTIVE", "COMPLETING") }

        override suspend fun getPausedSessions(): List<TranscriptionSessionEntity> =
            sessions.values.filter { it.state.startsWith("PAUSED") }

        override suspend fun getLiveSourcePaths(): List<String> =
            sessions.values.map { it.filePath }

        override suspend fun deleteSessionRow(taskId: String) {
            sessions.remove(taskId)
        }

        override suspend fun deleteChunksOf(taskId: String) {
            chunks.remove(taskId)
        }

        override suspend fun insertChunk(chunk: SessionChunkEntity) {
            chunks.getOrPut(chunk.taskId) { mutableListOf() }.add(chunk)
        }

        override suspend fun getChunks(taskId: String): List<SessionChunkEntity> =
            chunks[taskId]?.sortedBy { it.startSample } ?: emptyList()
    }

    private val sessionDao = FakeSessionDao()
    private lateinit var backend: TranscriptionBackend

    // Phase 6 device-leg finding: the fixture must be HONEST (header
    // total == decoded amount). The old 1s chunks under a 2400s header
    // encoded exactly the silent-truncation class the 3h device proof
    // caught (a 79% shortfall completing SUCCESS); the early-EOS guard
    // now parks such a run. Sixty 40s chunks cover the 2400s header
    // exactly; ONE shared FloatArray keeps the fixture at ~2.5 MB.
    private val chunkSeconds = 40L
    private val chunkSamples = 16000L * chunkSeconds
    private val chunkCount = 60
    private val sharedChunkSamples: FloatArray = FloatArray(chunkSamples.toInt())
    private val taskId = "kill-run"

    /** Phase 4 review: the eligibility probe is floor-gated on file size
     *  (1 MB), so the fixture must be a REAL file above it or the session
     *  never engages at dispatch and the header arm cannot run either. */
    private val sessionFile: java.io.File = java.io.File.createTempFile("session40", ".wav").apply {
        java.io.RandomAccessFile(this, "rw").setLength(1_200_000)
        deleteOnExit()
    }

    /** The start offset the fake stream was LAST asked to decode from. */
    private var lastStreamOffset = -1L

    private fun chunkAt(index: Int): FloatArray {
        // Only element 0 is ever read (stubDecode keys on samples[0]); a
        // full fill would be a 2.5 MB memset per emitted chunk.
        sharedChunkSamples[0] = index.toFloat()
        return sharedChunkSamples
    }

    private fun stubStream() {
        every {
            audioPreprocessor.prepareAudioStream(
                any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val offset = arg<Long>(8)
            flow {
                lastStreamOffset = offset
                emit(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    // The metadata duration lies long (the engagement
                    // authority): a 6-chunk stream that METADATA says is
                    // 40 minutes. The decoded samples are the content truth.
                    totalDurationSeconds = 2400.0,
                    expectedChunkCount = (chunkCount - (offset / chunkSamples).toInt()).coerceAtLeast(0),
                    startOffsetSamples = offset,
                )))
                val firstKept = (offset / chunkSamples).toInt()
                for (i in firstKept until chunkCount) {
                    emit(AudioPreprocessor.StreamEvent.Chunk(AudioPreprocessor.StreamChunk(
                        samples = chunkAt(i),
                        sampleRate = 16000,
                        chunkIndex = i - firstKept,
                        isLast = i == chunkCount - 1,
                    )))
                }
            }
        }
    }

    private fun stubBackend() {
        backend = mockk(relaxed = true) {
            every { id } returns "whisper"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            every { maxChunkDurationSeconds } returns 30
            every { displayName } returns "Whisper"
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { backendManager.activeBackendId } returns kotlinx.coroutines.flow.MutableStateFlow("whisper")
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
    }

    private fun stubDecode(answer: (Int) -> Result<TranscriptionResult>) {
        io.mockk.coEvery { backend.transcribeAudio(any(), any(), any()) } answers {
            answer(firstArg<FloatArray>()[0].toInt())
        }
    }

    /** The cues a token-less chunk of word i contributes (the positional cue). */
    private fun expectedCue(index: Int) =
        TimedSegment(startMs = index * chunkSeconds * 1000L, endMs = (index + 1) * chunkSeconds * 1000L, text = "w$index")

    /** The delivered transcript for the fixture: every chunk's token
     *  space-joined, with a paragraph break at each failed-gap index in
     *  [gaps] (the TASK-910 contract: a failed chunk leaves a cue time-gap
     *  that ParagraphBreaks renders as \n\n). */
    private fun expectedText(gaps: Set<Int> = emptySet()): String = buildString {
        for (i in 0 until chunkCount) {
            if (i in gaps) continue
            if (isNotEmpty()) append(if (i - 1 in gaps) "\n\n" else " ")
            append("w$i")
        }
    }

    /** The poisoned-engine decode: every chunk from [index] fails and
     *  stays failed through the retry (the 3h05 shape). The two tests
     *  sharing this must fail the SAME chunks (the second one's premise
     *  is the first one's parked state). */
    private fun stubPoisonedFrom(index: Int) {
        stubDecode { i ->
            if (i >= index) Result.failure(IllegalStateException("engine poisoned"))
            else Result.success(TranscriptionResult(text = "w$i"))
        }
    }

    @Before
    override fun baseSetUp() {
        super.baseSetUp()
        stubDefaultWhisperPreferences()
        stubBackend()
        every { preferencesManager.vadEnabled } returns flowOf(false)
        every { preferencesManager.progressiveTranscription } returns flowOf(false)
        every { preferencesManager.speakerLabelsEnabled } returns flowOf(false)
        every { audioPreprocessor.getAudioDuration(sessionFile.absolutePath) } returns
            (chunkCount * 10_000).toDouble() // far above the 30-minute threshold
        // Rebuild the orchestrator with the REAL fake session store (the base
        // uses a relaxed mock for the shared suites).
        orchestrator = TranscriptionOrchestrator(
            preferencesManager, logDao, transcriptionCalibrator, backendManager, audioPreprocessor,
            staticRegistry(), mockk(relaxed = true), mockk(relaxed = true), fakeStore,
            silentModelDemoter,
            OomBreadcrumbRecorder(preferencesManager, backendManager, llmManager, staticRegistry()),
            com.antivocale.app.transcription.diarization.SpeakerIdentityStore(
                java.io.File.createTempFile("speaker-ids", null).let { f -> f.delete(); f.mkdirs(); f }),
            sessionDao,
        )
        // Capture the segments the success funnel delivers (arg 10 of onSuccess)
        // and the partial flags (args 7/8: isPartial, failedChunkCount).
        every { listener.onSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            lastSegments = args[10] as? List<TimedSegment> ?: emptyList()
            lastIsPartial = args[7] as Boolean
            lastFailedChunkCount = args[8] as Int
        }
    }

    private var lastSegments: List<TimedSegment> = emptyList()
    private var lastIsPartial: Boolean = false
    private var lastFailedChunkCount: Int = 0

    /** prepareSessionResume requires the History row to exist (the base's
     *  logDao stub answers null, which reads as a deleted row). */
    private fun stubRow(taskId: String) {
        io.mockk.coEvery { logDao.getByTaskId(taskId) } returns com.antivocale.app.data.local.LogEntity(
            id = "id-$taskId", timestamp = 1L, taskId = taskId,
            type = "AUDIO", status = "PAUSED", filePath = sessionFile.absolutePath)
    }

    private suspend fun request(id: String = taskId) = orchestrator.processRequest(
        taskId = id, requestType = "audio", prompt = "",
        filePath = sessionFile.absolutePath, source = "share", sourcePackage = "org.telegram.messenger",
        queuePosition = 1, queueTotal = 1,
        context = mockk(relaxed = true), cacheDir = java.io.File("/tmp"),
        listener = listener, coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    @Test
    fun `resumed output equals uninterrupted output in text and cues`() = runTest {
        // Baseline: one uninterrupted run of the whole file.
        stubStream()
        val baseline = request("uninterrupted")
        val fullText = (0 until chunkCount).joinToString(" ") { "w$it" }
        assertEquals(fullText, baseline.getOrNull())
        val baselineText = baseline.getOrNull()
        val baselineCues = lastSegments
        assertEquals((0 until chunkCount).map { expectedCue(it) }, baselineCues)
        // The baseline run engaged and then TERMINALLY deleted its session.
        assertNull(sessionDao.sessions["uninterrupted"])

        // Kill stint: chunks 0-2 checkpointed, then the pause sentinel parks
        // the stint; the state rewrite below is exactly what the cold-start
        // recovery writes after a process death (PAUSED_SYSTEM).
        stubStream()
        stubDecode { index ->
            if (index == 2) {
                // Phase 4: the gate must arm for the live stint.
                assertTrue("a mid-stint pause must arm", orchestrator.requestSessionPause(taskId))
            }
            Result.success(TranscriptionResult(text = "w$index"))
        }
        val stint1 = request()
        assertTrue(stint1.isFailure)
        val parked = sessionDao.sessions[taskId]
        assertNotNull("the stint must have parked a session", parked)
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, parked!!.state)
        // Phase 4: the pipeline's finally cleared the stint tracker with the
        // park; a Pause tap on the parked row is refused (nothing live), so
        // the resume cannot be ambushed at its first boundary.
        assertFalse("a parked stint must not be pausable", orchestrator.requestSessionPause(taskId))
        assertEquals(3, sessionDao.getChunks(taskId).size)
        assertEquals(3 * chunkSamples, sessionDao.contiguousPrefixEnd(taskId))
        sessionDao.upsertSession(parked.copy(state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM))

        // Resume: the store drives everything; output must equal baseline.
        stubStream()
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
        val resumed = request()
        assertEquals(baselineText, resumed.getOrNull())
        assertEquals(baselineCues, lastSegments)
        assertEquals("the resumed decode must start at the store's prefix end",
            3 * chunkSamples, lastStreamOffset)
        // The terminal transition deleted the session rows.
        assertNull(sessionDao.sessions[taskId])
        // Phase 4: completion clears the tracker too; a late Pause tap on
        // the finished run arms nothing.
        assertFalse("a completed session must not be pausable",
            orchestrator.requestSessionPause(taskId))
        // The no-seed rule: no stint of a session run (fresh-engage or
        // resumed) may write the DataStore partial-transcription seed that
        // feeds the interrupted-run dialog (the plan's correction 1; the
        // simplify round caught the fresh-engage path missing it).
        io.mockk.coVerify(exactly = 0) { preferencesManager.savePartialTranscriptionState(any()) }
    }

    @Test
    fun `a user-marked cancel deletes the session, a scope teardown leaves it active`() = runTest {
        // Phase 4 review: coroutine activity cannot distinguish the two
        // CancellationException paths (the service-cancelled job is inactive
        // too), so the service's markUserCancel marker is the one signal.
        // Without it, explicitly cancelled work resurrects as a PAUSED_SYSTEM
        // session at the next cold start.
        stubStream()
        stubRow("user-cancel")
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "user-cancel",
            filePath = sessionFile.absolutePath, fileSizeBytes = 10L, fileMtimeMs = 42L,
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_ACTIVE,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        orchestrator.markUserCancel("user-cancel")
        orchestrator.cancelIfPending("user-cancel", errorMessage = "Transcription cancelled", durationMs = 0)
        assertNull("the user-marked cancel must delete the session",
            sessionDao.sessions["user-cancel"])

        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "scope-teardown",
            filePath = sessionFile.absolutePath, fileSizeBytes = 10L, fileMtimeMs = 42L,
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_ACTIVE,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        orchestrator.cancelIfPending("scope-teardown", errorMessage = "Transcription cancelled", durationMs = 0)
        assertNotNull("a scope teardown must leave the session ACTIVE for recovery",
            sessionDao.sessions["scope-teardown"])
        assertEquals(TranscriptionSessionEntity.STATE_ACTIVE,
            sessionDao.sessions["scope-teardown"]!!.state)
    }

    @Test
    fun `a decode that ends far short of the header parks instead of reporting success`() = runTest {
        // The 3h device-leg class: the header promises 2400s, the stream
        // delivers 3 of 6 chunks then EOS. The early-EOS guard must park
        // (text kept, resumable), never complete SUCCESS at 50%.
        stubStream()
        every { audioPreprocessor.prepareAudioStream(
            any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val offset = arg<Long>(8)
            flow {
                lastStreamOffset = offset
                emit(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 2400.0,
                    expectedChunkCount = 6,
                    startOffsetSamples = offset,
                )))
                val firstKept = (offset / chunkSamples).toInt()
                // TRUNCATION: deliver only 3 chunks from the offset
                for (i in firstKept until firstKept + 3) {
                    emit(AudioPreprocessor.StreamEvent.Chunk(AudioPreprocessor.StreamChunk(
                        samples = chunkAt(i),
                        sampleRate = 16000,
                        chunkIndex = i - firstKept,
                        isLast = i == firstKept + 2,
                    )))
                }
            }
        }
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
        stubRow(taskId)
        val result = request()
        assertTrue("the truncated run parks, not succeeds", result.isFailure)
        val parked = sessionDao.sessions[taskId]
        assertNotNull(parked)
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, parked!!.state)
        assertEquals("3 chunks checkpointed", 3, sessionDao.getChunks(taskId).size)
        io.mockk.coVerify(atLeast = 1) { logDao.parkProcessingAsPaused(taskId, any()) }
    }

    @Test
    fun `a decode shortfall within the drift tolerance completes success`() = runTest {
        // A 60s shortfall (tag drift / resampler fuzz) must not park: the
        // guard's tolerance band. Header 2400s, delivered 2340s.
        stubStream()
        every { audioPreprocessor.prepareAudioStream(
            any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            flow {
                emit(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 2400.0,
                    expectedChunkCount = 6,
                    startOffsetSamples = 0L,
                )))
                // 59 of the 60 full chunks = 2360s of 2400s: 40s short,
                // inside the 60s tolerance (the tag-drift class).
                for (i in 0 until chunkCount - 1) {
                    emit(AudioPreprocessor.StreamEvent.Chunk(AudioPreprocessor.StreamChunk(
                        samples = chunkAt(i),
                        sampleRate = 16000,
                        chunkIndex = i,
                        isLast = i == chunkCount - 2,
                    )))
                }
            }
        }
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
        val result = request("tolerated-drift")
        assertEquals((0 until chunkCount - 1).joinToString(" ") { "w$it" }, result.getOrNull())
        assertNull("no session survives the completed run", sessionDao.sessions["tolerated-drift"])
    }

    @Test
    fun `a stint whose majority of chunks fail at inference parks instead of partial success`() = runTest {
        // The 3h05 pause-leg class: the decode is COMPLETE (the early-EOS
        // guard's domain never fires) but the engine fails every call past
        // an offset, each failure retried and failing again. The run must
        // park (text kept, resumable), never complete a partial SUCCESS at
        // half the content. Chunks 29..59 fail: 31 of 60 sits strictly over
        // the majority threshold (exactly half must NOT park).
        stubStream()
        stubPoisonedFrom(29)
        stubRow(taskId)
        val result = request()
        assertTrue("the majority-failed run parks, not succeeds", result.isFailure)
        val parked = sessionDao.sessions[taskId]
        assertNotNull(parked)
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, parked!!.state)
        assertEquals("successful chunks checkpointed", 29, sessionDao.getChunks(taskId).size)
        assertEquals(29 * chunkSamples, sessionDao.contiguousPrefixEnd(taskId))
        io.mockk.coVerify(atLeast = 1) { logDao.parkProcessingAsPaused(taskId, any()) }
    }

    @Test
    fun `a minority-failed stint still completes partial success`() = runTest {
        // The tolerance arm: two failed chunks of 60 (3%) stay the per-chunk
        // non-fatal behavior (SUCCESS, isPartial, counted failures), exactly
        // as before the guard.
        stubStream()
        stubDecode { index ->
            if (index == 7 || index == 21) Result.failure(IllegalStateException("transient"))
            else Result.success(TranscriptionResult(text = "w$index"))
        }
        val result = request("minority-fail")
        assertEquals(expectedText(setOf(7, 21)), result.getOrNull())
        assertTrue("a minority-failed run is still marked partial", lastIsPartial)
        assertEquals(2, lastFailedChunkCount)
        assertNull("no session survives the completed run", sessionDao.sessions["minority-fail"])
    }

    @Test
    fun `a resumed stint with inherited majority failures completes and reports only its own`() = runTest {
        // Two pins on the stint-local choice, shaped so BOTH discriminate.
        // Stint 1: chunks 0..28 succeed, 29..57 FAIL (29), 58 succeeds and
        // requests the pause; the park row therefore carries
        // failedChunkCount=29 (written by 58's checkpoint, past the
        // failures) while the contiguous prefix stays at 29 chunks.
        // RATIO: the resume re-runs 29..59; cumulative counting (29
        // inherited + 2 new = 31 failed of 60 processed, a majority) would
        // park the healing stint forever, stint-local counting (2/31)
        // completes it. REPORTING: the completed run owes only its OWN two
        // unresolved failures (failedChunkCount 2, never the 31 cumulative
        // ghost count).
        stubStream()
        stubDecode { index ->
            if (index == 58) {
                assertTrue("a mid-stint pause must arm", orchestrator.requestSessionPause(taskId))
            }
            if (index in 29..57) Result.failure(IllegalStateException("engine poisoned"))
            else Result.success(TranscriptionResult(text = "w$index"))
        }
        stubRow(taskId)
        assertTrue(request().isFailure)
        val parked = sessionDao.sessions[taskId]!!
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, parked.state)
        assertEquals("the park row carries the inherited failures (29, checkpointed past them)",
            29, parked.failedChunkCount)
        assertEquals("the contiguous prefix stops at the first failure",
            29 * chunkSamples, sessionDao.contiguousPrefixEnd(taskId))

        // What the cold-start recovery writes after a process death.
        sessionDao.upsertSession(parked.copy(state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM))
        stubStream()
        stubDecode { index ->
            if (index == 40 || index == 50) Result.failure(IllegalStateException("transient"))
            else Result.success(TranscriptionResult(text = "w$index"))
        }
        val resumed = request()
        assertEquals(expectedText(setOf(40, 50)), resumed.getOrNull())
        assertEquals("the resumed decode starts at the store's prefix end",
            29 * chunkSamples, lastStreamOffset)
        assertTrue("the two still-missing chunks mark the run partial", lastIsPartial)
        assertEquals("only the stint's own failures report (never the 31 cumulative)",
            2, lastFailedChunkCount)
        assertNull("the terminal transition deleted the session rows", sessionDao.sessions[taskId])
    }

    @Test
    fun `a short run engages no session`() = runTest {
        every { audioPreprocessor.getAudioDuration(sessionFile.absolutePath) } returns 12.0
        stubStream()
        val result = request("short-run")
        assertEquals((0 until chunkCount).joinToString(" ") { "w$it" }, result.getOrNull())
        assertTrue(sessionDao.sessions.isEmpty())
    }

    @Test
    fun `a non-session origin engages no session even on long audio`() = runTest {
        // Tasker requests carry NO source and are excluded by the gate.
        stubStream()
        val result = orchestrator.processRequest(
            taskId = "tasker-run", requestType = "audio", prompt = "",
            filePath = sessionFile.absolutePath, source = null, sourcePackage = null,
            queuePosition = 1, queueTotal = 1,
            context = mockk(relaxed = true), cacheDir = java.io.File("/tmp"),
            listener = listener, coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        assertTrue(result.isSuccess)
        assertTrue(sessionDao.sessions.isEmpty())
    }

    @Test
    fun `resume refuses on a missing file and closes partial with the reason`() = runTest {
        stubStream()
        stubRow("refused")
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "refused",
            filePath = sessionFile.absolutePath, fileSizeBytes = 10L, fileMtimeMs = 42L,
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_USER,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val plan = orchestrator.prepareSessionResume("refused", mockk(relaxed = true))
        assertTrue(plan is TranscriptionOrchestrator.SessionResumePlan.Closed)
        assertTrue(plan.toString().contains("file"))
        assertNull(sessionDao.sessions["refused"])
    }

    @Test
    fun `resume refuses when the frozen model path changed and closes partial`() = runTest {
        // A real file with a matching fingerprint, so the model check is the
        // gate that fires: the frozen /elsewhere path differs from the
        // active preference's /models/whisper (a variant switch between stints).
        val file = java.io.File.createTempFile("session-src", ".wav")
        file.writeText("audio")
        stubStream()
        stubRow("model-moved")
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "model-moved",
            filePath = file.absolutePath, fileSizeBytes = file.length(),
            fileMtimeMs = file.lastModified(),
            backendId = "whisper", modelPath = "/elsewhere/whisper-v1",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val plan = orchestrator.prepareSessionResume("model-moved", mockk(relaxed = true))
        assertTrue(plan is TranscriptionOrchestrator.SessionResumePlan.Closed)
        assertTrue(plan.toString().contains("model"))
        assertNull(sessionDao.sessions["model-moved"])
    }

    @Test
    fun `a completing-session resume skips the decode and rebuilds from the store`() = runTest {
        stubStream()
        lastStreamOffset = -1L
        // Plant the post-stream state: all six chunks checkpointed, the
        // total recorded at the COMPLETING transition, then parked by the
        // recovery (PAUSED_SYSTEM). The plan's "resume skips decode and
        // reruns post-passes" row.
        var cursor = 0L
        for (i in 0 until chunkCount) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "completing",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
                cuesJson = com.antivocale.app.data.local.TimedSegmentsConverter.toJson(
                    listOf(expectedCue(i))),
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "completing",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            totalOutputSamples = cursor,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val result = request("completing")
        assertEquals((0 until chunkCount).joinToString(" ") { "w$it" }, result.getOrNull())
        assertEquals((0 until chunkCount).map { expectedCue(it) }, lastSegments)
        assertEquals("no decode may run for a completing resume", -1L, lastStreamOffset)
        assertNull(sessionDao.sessions["completing"])
    }

    @Test
    fun `a completing resume with a switched active backend loads the session's frozen one`() = runTest {
        // TASK-1048: the completing arm must resolve the FROZEN backend, not
        // the ambient active one. The pin: a DIFFERENT backend is active when
        // the resume runs; the only way the run completes with the store's
        // chunks is the fix loading the frozen id (observed as the
        // setActiveBackend("whisper") request flipping the active mock).
        stubStream()
        lastStreamOffset = -1L
        val parakeet = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "parakeet"
            every { isReady() } returns true
            every { isAudioSupported() } returns true
            every { supportsAudio } returns true
            every { maxChunkDurationSeconds } returns 30
            every { displayName } returns "Parakeet"
        }
        var current: TranscriptionBackend = parakeet
        every { backendManager.getActiveBackend() } answers { current }
        io.mockk.coEvery { backendManager.setActiveBackend(any(), any(), any()) }.coAnswers {
            current = if (firstArg<String>() == "whisper") backend else parakeet
            Result.success(Unit)
        }
        var cursor = 0L
        for (i in 0 until chunkCount) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "switched",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "switched",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            totalOutputSamples = cursor,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        stubRow("switched")
        // A REAL directory for the whisper model path: the load honors the
        // saved path when it exists and skips the catalog auto-resolution
        // walk (which needs a real filesystem).
        val whisperDir = java.io.File.createTempFile("whisper", ".dir").let {
            it.delete(); it.mkdirs(); it }
        every { preferencesManager.sherpaModelPath("whisper") } returns
            kotlinx.coroutines.flow.flowOf(whisperDir.absolutePath)
        val result = request("switched")
        val outcome = result.fold(
            { it },
            { "FAILURE: ${it.message} (cause ${it.cause?.message})" })
        assertEquals("the completing arm rebuilds from the store on the frozen backend",
            (0 until chunkCount).joinToString(" ") { "w$it" }, outcome)
        io.mockk.coVerify(atLeast = 1) {
            backendManager.setActiveBackend("whisper", any(), any())
        }
        // The restore: the base stubs answer the singleton whisper mock.
        every { backendManager.getActiveBackend() } returns backend
    }

    @Test
    fun `a crash-loop tombstone counts as unresolved content but never parks the stint`() = runTest {
        // TASK-1044: two shapes in one pin. The session sits at offset zero
        // with the crash counter past the threshold, so the resume writes a
        // chunk-cap tombstone (80s = 2 fixture chunks) and processes only
        // the third. DELIVERY: the completed run reports the skipped range
        // as missing content (isPartial, failedChunkCount 2) and the +1s
        // survive every later checkpoint ride-along (the final funnel read
        // IS the last sink state). PARK RATIO: without the tombstone
        // exclusion the cumulative math (2 failed of 1 processed this
        // stint) would park the healing stint forever.
        every { backend.maxChunkDurationSeconds } returns 80
        stubStream()
        // A 3-chunk stream (the class stub emits 60; re-stub to the short
        // file so the tombstone's skip dominates the stint's own work).
        every { audioPreprocessor.prepareAudioStream(
            any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val offset = arg<Long>(8)
            flow {
                emit(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = 3 * chunkSeconds.toDouble(),
                    expectedChunkCount = (3 - (offset / chunkSamples).toInt()).coerceAtLeast(0),
                    startOffsetSamples = offset,
                )))
                val firstKept = (offset / chunkSamples).toInt()
                for (i in firstKept until 3) {
                    emit(AudioPreprocessor.StreamEvent.Chunk(AudioPreprocessor.StreamChunk(
                        samples = chunkAt(i),
                        sampleRate = 16000,
                        chunkIndex = i - firstKept,
                        isLast = i == 2,
                    )))
                }
            }
        }
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
        stubRow(taskId)
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = taskId,
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            crashAtOffsetCount = 2,
            totalOutputSamples = 3 * chunkSamples,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val result = request()
        // Only the third chunk reached the engine; the first two are the
        // tombstone's skip.
        assertEquals("w2", result.getOrNull())
        assertTrue("the skipped range marks the run partial", lastIsPartial)
        assertEquals("the cap-sized skip counts as one unresolved chunk at delivery",
            1, lastFailedChunkCount)
        assertNull("the terminal transition deleted the session rows", sessionDao.sessions[taskId])
        // The restore: the cap goes back to the base stub's value.
        every { backend.maxChunkDurationSeconds } returns 30
    }

    @Test
    fun `a zero-progress stint parks and stamps the no-progress counter`() = runTest {
        // TASK-1046: the recorded total overstates the real content by
        // 80s (> the 60s tolerance). The resume starts at the prefix (all
        // real content checkpointed), decodes NOTHING past it (the stream
        // is exhausted), and still falls short: the early-EOS park fires
        // with zero progress and the session is stamped for the gate.
        stubStream()
        lastStreamOffset = -1L
        stubRow("stall-1")
        // The stream delivers only what the prefix already covers: past
        // the offset it emits the header and nothing else (the real
        // content ends at the prefix).
        every { audioPreprocessor.prepareAudioStream(
            any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val offset = arg<Long>(8)
            flow {
                emit(AudioPreprocessor.StreamEvent.Header(AudioPreprocessor.StreamHeader(
                    sampleRate = 16000,
                    totalDurationSeconds = (6 + 2) * chunkSeconds.toDouble(),
                    expectedChunkCount = 0,
                    startOffsetSamples = offset,
                )))
            }
        }
        var cursor = 0L
        for (i in 0 until 6) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "stall-1",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "stall-1",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            // The lie: 80s of content beyond the real end.
            totalOutputSamples = cursor + 2 * chunkSamples,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val result = request("stall-1")
        assertTrue("the zero-progress stint parks", result.isFailure)
        val stamped = sessionDao.sessions["stall-1"]
        assertNotNull(stamped)
        assertEquals("the zero-progress park increments the counter",
            1, stamped!!.noProgressParkCount)
        assertEquals(TranscriptionSessionEntity.STATE_PAUSED_USER, stamped.state)
    }

    @Test
    fun `a resume past a no-progress park closes as kept-text partial`() = runTest {
        // TASK-1046: the gate in the resume arm. The counter is on
        // record; another stint cannot make progress, so instead of
        // decoding (or parking again) the session closes as partial with
        // the text kept and the stated reason, ending the loop without
        // asking the user to Cancel their way out.
        stubStream()
        lastStreamOffset = -1L
        stubRow("stall-2")
        var cursor = 0L
        for (i in 0 until chunkCount) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "stall-2",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "stall-2",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            noProgressParkCount = 1,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val result = request("stall-2")
        assertTrue("the gated resume closes, not succeeds", result.isFailure)
        assertTrue("the reason names the overstated tag",
            result.exceptionOrNull()!!.message!!.contains("duration tag overstates"))
        assertEquals("the decode never starts (the loop is broken at the resume arm)",
            -1L, lastStreamOffset)
        assertNull("the session rows are deleted on the close", sessionDao.sessions["stall-2"])
        // The text survives on the History row (kept-text partial).
        val kept = io.mockk.slot<String>()
        io.mockk.coVerify(atLeast = 1) { logDao.updateInterimResult("stall-2", capture(kept), isPartial = true) }
        assertTrue("the kept text spans the whole prefix",
            kept.captured.contains("w0") && kept.captured.contains("w${chunkCount - 1}"))
        // The gate runs AFTER markProcessing promoted the row out of PAUSED
        // (the code-review zombie finding): the row must be parked back
        // BEFORE the close-as-partial arm runs, or the guarded
        // closePausedAsError UPDATE matches nothing and History keeps a
        // zombie in-flight row.
        io.mockk.coVerifyOrder {
            logDao.parkProcessingAsPaused("stall-2", null)
            logDao.closePausedAsError("stall-2", any())
        }
    }

    /** TASK-1060.4: speaker labels on, the windowed arm faked to label every cue speaker 0. */
    private fun armWindowedSpeakerPass(): MutableList<Long?> {
        every { preferencesManager.speakerLabelsEnabled } returns flowOf(true)
        every { preferencesManager.speakerIdEnabled } returns flowOf(false)
        val totals = mutableListOf<Long?>()
        orchestrator.ensureDiarizationModels = { Result.success(Unit) }
        orchestrator.wholeFileDiarization = { _, _, _, _ -> error("a session run must not retain its decode") }
        orchestrator.windowedDiarization = { _, filePath, totalSamples, _, _ ->
            assertEquals(sessionFile.absolutePath, filePath)
            totals += totalSamples
            com.antivocale.app.transcription.diarization.WindowedDiarization(
                listOf(com.antivocale.app.transcription.diarization.DiarizedSegment(
                    0f, (chunkCount * chunkSeconds).toFloat(), 0, null)),
                emptyMap(),
                com.antivocale.app.transcription.diarization.WindowedDiarizationStats(0, 0, 0, 0, 0, 0.0, 0, 0.0))
        }
        return totals
    }

    @Test
    fun `a resumed stint with speaker labels takes the windowed pass over the whole file`() = runTest {
        stubStream()
        stubDecode { index ->
            if (index == 2) assertTrue(orchestrator.requestSessionPause(taskId))
            Result.success(TranscriptionResult(text = "w$index"))
        }
        assertTrue(request().isFailure)
        sessionDao.upsertSession(sessionDao.sessions.getValue(taskId)
            .copy(state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM))

        val totals = armWindowedSpeakerPass()
        stubStream()
        stubDecode { index -> Result.success(TranscriptionResult(text = "w$index")) }
        val resumed = request()

        assertTrue(resumed.isSuccess)
        // The exact length of the WHOLE file, the earlier stint's prefix included.
        assertEquals(listOf<Long?>(chunkCount * chunkSamples), totals)
        assertEquals(chunkCount, lastSegments.size)
        assertTrue(lastSegments.all { it.speaker == 0 })
    }

    @Test
    fun `a completing-session resume with speaker labels takes the windowed pass`() = runTest {
        var cursor = 0L
        for (i in 0 until chunkCount) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "completing-speakers",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
                cuesJson = com.antivocale.app.data.local.TimedSegmentsConverter.toJson(
                    listOf(expectedCue(i))),
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "completing-speakers",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            totalOutputSamples = cursor,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val totals = armWindowedSpeakerPass()

        val result = request("completing-speakers")

        assertTrue(result.isSuccess)
        assertEquals(listOf<Long?>(cursor), totals)
        assertTrue(lastSegments.isNotEmpty())
        assertTrue(lastSegments.all { it.speaker == 0 })
    }

    @Test
    fun `a completing resume after a post-pass native death delivers unlabeled`() = runTest {
        // The recovery counted a native-crash or low-memory death after the
        // last checkpoint: the previous stint died inside the post-passes,
        // so this resume must not rerun the speaker pass into the same death.
        var cursor = 0L
        for (i in 0 until chunkCount) {
            sessionDao.insertChunk(SessionChunkEntity(
                taskId = "completing-crashed",
                startSample = cursor,
                endSample = cursor + chunkSamples,
                text = "w$i",
                cuesJson = com.antivocale.app.data.local.TimedSegmentsConverter.toJson(
                    listOf(expectedCue(i))),
            ))
            cursor += chunkSamples
        }
        sessionDao.upsertSession(TranscriptionSessionEntity(
            taskId = "completing-crashed",
            filePath = sessionFile.absolutePath,
            fileSizeBytes = sessionFile.length(), fileMtimeMs = sessionFile.lastModified(),
            backendId = "whisper", modelPath = "/models/whisper",
            source = "share", state = TranscriptionSessionEntity.STATE_PAUSED_SYSTEM,
            totalOutputSamples = cursor,
            crashAtOffsetCount = 1,
            createdAtMs = 1L, updatedAtMs = 1L,
        ))
        val totals = armWindowedSpeakerPass()

        val result = request("completing-crashed")

        assertEquals((0 until chunkCount).joinToString(" ") { "w$it" }, result.getOrNull())
        assertTrue("the speaker pass must not run", totals.isEmpty())
        assertEquals((0 until chunkCount).map { expectedCue(it) }, lastSegments)
        assertNull(sessionDao.sessions["completing-crashed"])
    }
}
