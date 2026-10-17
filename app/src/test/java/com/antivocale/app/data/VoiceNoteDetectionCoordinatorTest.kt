package com.antivocale.app.data

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-272: the detection lifecycle's decision layer (the automatic form:
 * every detected note is handed to the auto-transcriber). The pure policy
 * rules run device-free; the coordinator transitions run against a mocked
 * detector (the real one needs a filesystem), with real timing only where
 * a background collector must observe a signal.
 */
class VoiceNoteDetectionCoordinatorTest {

    private fun entry(path: String? = "/voice/PTT-1.opus") = VoiceNoteEntry(
        id = 7L,
        path = path,
        detectedAtMs = 1_000L,
    )

    // ---- coordinator transitions ----

    private class Harness(
        private val permissionGranted: () -> Boolean,
    ) {
        val detections = MutableSharedFlow<VoiceNoteEntry>(extraBufferCapacity = 8)
        val detector = mockk<VoiceNoteDetector>()
        val enqueued = mutableListOf<String>()
        val enqueuedUris = mutableListOf<String?>()
        val preferences = FakePreferencesManager()
        val treeGrant = mockk<VoiceNoteTreeGrant>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = VoiceNoteDetectionCoordinator(
            detector,
            preferences,
            treeGrant,
            mockk<VoiceNoteAutoTranscriber> {
                coEvery { enqueue(any(), any(), any()) } coAnswers {
                    enqueued += firstArg<String>()
                    enqueuedUris += thirdArg<String?>()
                }
            },
            VoiceNotePermissionChecker { permissionGranted() },
            scope,
        )

        init {
            // The coordinator gates detection on the SAF tree grant (the
            // one read route); a harness default of null would fail every
            // shouldRun conjunction, not just the grant's own tests.
            preferences._voiceNoteTreeUri.value = TREE_URI
            every { treeGrant.holdsGrant(TREE_URI) } returns true
            every { detector.detections } returns detections
            every { detector.subscribe() } just Runs
            every { detector.dispose() } just Runs
            every { detector.refresh() } just Runs
        }

        /**
         * The collector runs on Dispatchers.IO, so the test re-emits the
         * entry until the enqueue lands rather than sleeping a guess: the
         * first emission can race the subscription, and a buffered one can
         * be consumed late.
         */
        suspend fun untilEnqueued(e: VoiceNoteEntry) {
            withTimeout(5_000) {
                while (enqueued.isEmpty()) {
                    detections.tryEmit(e)
                    delay(10)
                }
            }
        }

        /**
         * One emission, then a grace delay: the collector either enqueues
         * or skips the entry; both settle within the delay.
         */
        suspend fun emitAndSettle(e: VoiceNoteEntry) {
            detections.tryEmit(e)
            delay(100)
        }
    }

    private companion object {
        const val TREE_URI = "content://tree/test"
    }

    @Test
    fun `on with permission subscribes and enqueues one transcription`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 1) { h.detector.subscribe() }
        h.untilEnqueued(entry())
        assertEquals(listOf("/voice/PTT-1.opus"), h.enqueued)
        // TASK-832: the LIVE uri travelled coordinator -> enqueue (a
        // regression to a null pass-through skips every note silently).
        assertEquals(listOf<String?>(TREE_URI), h.enqueuedUris)
        h.scope.cancel()
    }

    @Test
    fun `a re-picked grant swaps the live uri without re-arming`() = runBlocking {
        // TASK-832 review F1: the latest-wins field. A live-to-live re-pick
        // short-circuits reconcile (shouldRun == active, no re-arm), so the
        // STILL-RUNNING collector must see the NEW uri; capturing at arm
        // time would strand every later note on the released old grant.
        val h = Harness(permissionGranted = { true })
        every { h.treeGrant.holdsGrant(any()) } returns true
        h.coordinator.reconcile(prefEnabled = true, treeUri = "content://tree/a")
        h.coordinator.reconcile(prefEnabled = true, treeUri = "content://tree/b")
        // No second subscribe: the short-circuit path.
        verify(exactly = 1) { h.detector.subscribe() }
        h.untilEnqueued(entry())
        assertEquals(listOf<String?>("content://tree/b"), h.enqueuedUris)
        h.scope.cancel()
    }

    @Test
    fun `armed mirrors the reconcile outcome in both directions`() = runBlocking {
        // TASK-826: the SPI's voice_note_state reads this flag from a
        // binder thread, so the pin is the FLAG, not just the observable
        // side effects (subscribe/dispose already pinned by the siblings).
        val h = Harness(permissionGranted = { true })
        assertFalse(h.coordinator.armed)
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        assertTrue(h.coordinator.armed)
        h.coordinator.reconcile(prefEnabled = false, treeUri = TREE_URI)
        assertFalse(h.coordinator.armed)
        h.scope.cancel()
    }

    @Test
    fun `gateState is the one derivation the Settings card renders`() = runBlocking {
        // TASK-827: every reconcile pass publishes the conjunction's
        // outcome; the card renders this flow and nothing re-derives the
        // conjunction screen-side. The four states are pinned through the
        // same transitions the siblings drive.
        val h = Harness(permissionGranted = { true })
        assertEquals(VoiceNoteGateState.Inactive, h.coordinator.gateState.value)
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        assertEquals(VoiceNoteGateState.Active, h.coordinator.gateState.value)
        h.coordinator.reconcile(prefEnabled = false, treeUri = TREE_URI)
        assertEquals(VoiceNoteGateState.Inactive, h.coordinator.gateState.value)

        val deadGrant = Harness(permissionGranted = { true })
        every { deadGrant.treeGrant.holdsGrant(TREE_URI) } returns false
        deadGrant.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        assertEquals(VoiceNoteGateState.GrantMissing, deadGrant.coordinator.gateState.value)
        // The null-uri disjunct of the same branch (the Remove path's
        // production route): never supply a non-null URI alone.
        deadGrant.coordinator.reconcile(prefEnabled = true, treeUri = null)
        assertEquals(VoiceNoteGateState.GrantMissing, deadGrant.coordinator.gateState.value)

        val noPermission = Harness(permissionGranted = { false })
        noPermission.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        assertEquals(VoiceNoteGateState.PermissionMissing, noPermission.coordinator.gateState.value)

        h.scope.cancel(); deadGrant.scope.cancel(); noPermission.scope.cancel()
    }

    @Test
    fun `a burst queues every note for transcription`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        // A burst as back-to-back emissions, re-fired until the collector
        // (launched on a real IO dispatcher) is subscribed: TASK-272 AC3,
        // every detected note reaches the transcriber (the service queue
        // serializes; no note may be dropped at this seam).
        withTimeout(5_000) {
            while (h.enqueued.size < 3) {
                h.detections.tryEmit(entry("/voice/PTT-1.opus").copy(id = 1L))
                h.detections.tryEmit(entry("/voice/PTT-2.opus").copy(id = 2L))
                h.detections.tryEmit(entry("/voice/PTT-3.opus").copy(id = 3L))
                delay(10)
            }
        }
        assertEquals(
            setOf("/voice/PTT-1.opus", "/voice/PTT-2.opus", "/voice/PTT-3.opus"),
            h.enqueued.toSet())
        h.scope.cancel()
    }

    @Test
    fun `an entry without a path enqueues nothing even when detected`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        h.emitAndSettle(entry(path = null))
        assertTrue(h.enqueued.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun `off disposes the detector`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        h.coordinator.reconcile(prefEnabled = false, treeUri = TREE_URI)
        verify(exactly = 1) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `a missing permission keeps the detector unsubscribed`() = runBlocking {
        val h = Harness(permissionGranted = { false })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 0) { h.detector.subscribe() }
        h.scope.cancel()
    }

    @Test
    fun `rapid toggling re-arms the detector`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        h.coordinator.reconcile(prefEnabled = false, treeUri = TREE_URI)
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 2) { h.detector.subscribe() }
        verify(exactly = 1) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `a saved uri whose live grant died keeps the gate closed`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        // The preference string survives (folder recreated, OEM cleanup);
        // the live-grant probe is what must disarm transcription.
        every { h.treeGrant.holdsGrant(TREE_URI) } returns false
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 0) { h.detector.subscribe() }
        h.scope.cancel()
    }

    @Test
    fun `a permission poke re-evaluates the gate without a preference change`() = runBlocking {
        var granted = false
        val h = Harness(permissionGranted = { granted })
        h.preferences._voiceNoteDetectionEnabled.value = true
        // Before any collector: the permission is missing, so the gate
        // stays closed even with the preference on.
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 0) { h.detector.subscribe() }
        // The grant arrives with NO preference change; only the poke (here
        // the start-up one, which shares the re-evaluation path) re-arms.
        granted = true
        h.coordinator.start()
        verify(timeout = 5_000) { h.detector.subscribe() }
        // And a mid-flight revoke via poke tears detection back down.
        granted = false
        h.coordinator.pokePermissionState()
        verify(timeout = 5_000) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `a missing tree grant keeps the gate closed and a later grant re-arms it`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.preferences._voiceNoteDetectionEnabled.value = true
        // The harness defaults the grant to a live tree; drop it and the
        // gate must stay closed even with permission and preference on:
        // without the grant every resolve would fail.
        h.preferences._voiceNoteTreeUri.value = null
        h.coordinator.start()
        verify(exactly = 0) { h.detector.subscribe() }
        // The Settings pick saves the tree: the flow source re-arms with no
        // poke, the same shape as the preference flow.
        h.preferences._voiceNoteTreeUri.value = TREE_URI
        verify(timeout = 5_000) { h.detector.subscribe() }
        // And removing the access (the card's Rimuovi accesso) tears
        // detection down.
        h.preferences._voiceNoteTreeUri.value = null
        verify(timeout = 5_000) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `start wires the preference flow into the lifecycle`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.start()
        // A second start must not double-collect.
        h.coordinator.start()
        h.preferences._voiceNoteDetectionEnabled.value = true
        h.untilEnqueued(entry())
        verify(atLeast = 1) { h.detector.subscribe() }
        h.preferences._voiceNoteDetectionEnabled.value = false
        // Blocking verify: the collector runs on a real dispatcher.
        verify(timeout = 5_000) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `while armed the detector is blindly re-subscribed on a cadence`() = runBlocking {
        // TASK-830: the watches can die silently (the state lies), so the
        // cure is periodic refresh, not checks. Driven through reconcile
        // directly (the lifecycle idiom of this file): dispose completes
        // inside reconcile(false), so the post-disarm count is exact.
        var refreshes = 0
        val h = Harness({ true })
        every { h.detector.refresh() } answers { refreshes++ }
        h.coordinator.rearmIntervalMs = 100L
        h.coordinator.reconcile(true, TREE_URI)
        delay(400)
        assertTrue("cadence loop ran (refreshes=$refreshes)", refreshes >= 2)
        h.coordinator.reconcile(false, TREE_URI)
        val atDisarm = refreshes
        delay(250)
        assertEquals("disarm stopped the cadence", atDisarm.toLong(), refreshes.toLong())
        h.scope.cancel()
    }
}
