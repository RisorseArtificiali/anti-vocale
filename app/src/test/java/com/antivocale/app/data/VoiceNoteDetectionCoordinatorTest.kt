package com.antivocale.app.data

import io.mockk.Runs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-271: the detection lifecycle's decision layer. The pure policy rules
 * run device-free; the coordinator transitions run against a mocked
 * detector (the real one needs a ContentResolver), with real timing only
 * where a background collector must observe a signal.
 */
class VoiceNoteDetectionCoordinatorTest {

    private fun entry(path: String? = "/voice/PTT-1.opus") = VoiceNoteEntry(
        id = 7L,
        path = path,
        ownerPackage = "com.whatsapp",
    )

    // ---- coordinator transitions ----

    private class Harness(
        private val permissionGranted: () -> Boolean,
        posterOverride: VoiceNoteOfferPoster? = null,
    ) {
        val detections = MutableSharedFlow<VoiceNoteEntry>(extraBufferCapacity = 8)
        val detector = mockk<VoiceNoteDetector>()
        val offers = mutableListOf<VoiceNoteOffer>()
        val preferences = FakePreferencesManager()
        val treeGrant = mockk<VoiceNoteTreeGrant>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = VoiceNoteDetectionCoordinator(
            detector,
            preferences,
            treeGrant,
            posterOverride ?: VoiceNoteOfferPoster { offers += it },
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
        }

        /**
         * The collector runs on Dispatchers.IO, so the test re-emits the
         * entry until the offer lands rather than sleeping a guess: the
         * first emission can race the subscription, and a buffered one can
         * be consumed late.
         */
        suspend fun untilOfferPosted(e: VoiceNoteEntry) {
            withTimeout(5_000) {
                while (offers.isEmpty()) {
                    detections.tryEmit(e)
                    delay(10)
                }
            }
        }

        /**
         * One emission, then a grace delay: the collector either posts an
         * offer or skips the entry; both settle within the delay.
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
    fun `on with permission subscribes and posts one sender-less offer`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        verify(exactly = 1) { h.detector.subscribe() }
        h.untilOfferPosted(entry())
        assertEquals(1, h.offers.size)
        // Sender-less AND duration-less by design on this route (code
        // review F1): the observed path is FUSE-hidden, a duration probe
        // would always fail, and the matcher labels nothing without one.
        assertNull(h.offers.single().senderName)
        assertNull(h.offers.single().durationSeconds)
        h.scope.cancel()
    }

    @Test
    fun `a burst offers only the newest entry`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        // A burst as back-to-back emissions, re-fired until the collector
        // (launched on a real IO dispatcher) is subscribed: whether a burst
        // lands as one (real-flow conflation) or both (this harness buffers
        // 8), the LAST offer is the newest entry and the single fixed slot
        // shows that one; an older offer never survives as visible state.
        withTimeout(5_000) {
            while (h.offers.isEmpty()) {
                h.detections.tryEmit(entry("/voice/PTT-1.opus").copy(id = 1L))
                h.detections.tryEmit(entry("/voice/PTT-2.opus").copy(id = 2L))
                delay(10)
            }
        }
        assertEquals("/voice/PTT-2.opus", h.offers.last().filePath)
        h.scope.cancel()
    }

    @Test
    fun `a suppressed notification channel posts nothing`() = runBlocking {
        val h = Harness(
            permissionGranted = { true },
            posterOverride = object : VoiceNoteOfferPoster {
                override fun canPost() = false
                override fun post(offer: VoiceNoteOffer) {}
            },
        )
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        h.emitAndSettle(entry())
        assertTrue(h.offers.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun `an entry without a path posts nothing even when detected`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true, treeUri = TREE_URI)
        h.emitAndSettle(entry(path = null))
        assertTrue(h.offers.isEmpty())
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
        // the live-grant probe is what must disarm the offers.
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
        // without the grant every tap would cancel its offer.
        h.preferences._voiceNoteTreeUri.value = null
        h.coordinator.start()
        verify(exactly = 0) { h.detector.subscribe() }
        // The Settings pick saves the tree: the flow source re-arms with no
        // poke, the same shape as the preference flow.
        h.preferences._voiceNoteTreeUri.value = TREE_URI
        verify(timeout = 5_000) { h.detector.subscribe() }
        // And clearing it (the rejected-pick path) tears detection down.
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
        h.untilOfferPosted(entry())
        verify(atLeast = 1) { h.detector.subscribe() }
        h.preferences._voiceNoteDetectionEnabled.value = false
        // Blocking verify: the collector runs on a real dispatcher.
        verify(timeout = 5_000) { h.detector.dispose() }
        h.scope.cancel()
    }
}
