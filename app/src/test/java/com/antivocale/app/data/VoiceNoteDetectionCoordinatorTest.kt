package com.antivocale.app.data

import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.receiver.VoiceNoteIdentityCache
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
        dateAddedSeconds = 100L,
        displayName = "PTT-1.opus",
        path = path,
        relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/",
        mimeType = "audio/opus",
        ownerPackage = "com.whatsapp",
    )

    // ---- coordinator transitions ----

    private class Harness(
        private val permissionGranted: () -> Boolean,
        posterOverride: VoiceNoteOfferPoster? = null,
    ) {
        val signals = MutableSharedFlow<VoiceNoteDetector.ChangeSignal>(extraBufferCapacity = 8)
        val detector = mockk<VoiceNoteDetector>()
        val offers = mutableListOf<VoiceNoteOffer>()
        val preferences = FakePreferencesManager()
        val preprocessor = mockk<AudioPreprocessor>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pollCount = java.util.concurrent.atomic.AtomicInteger()
        val coordinator = VoiceNoteDetectionCoordinator(
            detector,
            preferences,
            posterOverride ?: VoiceNoteOfferPoster { offers += it },
            VoiceNoteIdentityCache(),
            preprocessor,
            VoiceNotePermissionChecker { permissionGranted() },
            scope,
        )

        init {
            every { detector.changeSignals() } returns signals
            every { detector.subscribe() } just Runs
            every { detector.dispose() } just Runs
            coEvery { detector.poll() } answers {
                pollCount.incrementAndGet()
                emptyList()
            }
            every { preprocessor.getAudioDuration(any()) } returns 42.5
        }

        fun pollReturnsOnce(entries: List<VoiceNoteEntry>) {
            coEvery { detector.poll() } answers {
                pollCount.incrementAndGet()
                if (pollCount.get() == 1) entries else emptyList()
            }
        }

        /**
         * The collector runs on Dispatchers.IO, so the test re-emits the
         * signal until the offer lands rather than sleeping a guess: the
         * first emission can race the subscription, and a buffered one can
         * be consumed late.
         */
        suspend fun untilOfferPolledOnce() {
            withTimeout(5_000) {
                while (offers.isEmpty()) {
                    signals.tryEmit(VoiceNoteDetector.ChangeSignal.MediaChanged)
                    delay(10)
                }
            }
        }

        /** Same race shape, for polls that produce no offer at all. */
        suspend fun untilPolled(pollsNeeded: Int) {
            withTimeout(5_000) {
                while (pollCount.get() < pollsNeeded) {
                    signals.tryEmit(VoiceNoteDetector.ChangeSignal.MediaChanged)
                    delay(10)
                }
            }
        }
    }

    @Test
    fun `on with permission subscribes and posts one sender-less offer with the duration`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.pollReturnsOnce(listOf(entry()))
        h.coordinator.reconcile(prefEnabled = true)
        verify(exactly = 1) { h.detector.subscribe() }
        h.untilOfferPolledOnce()
        assertEquals(1, h.offers.size)
        // Nothing cached for the owner package: no guessed sender, but the
        // probed duration still rides the offer.
        assertNull(h.offers.single().senderName)
        assertEquals(42L, h.offers.single().durationSeconds)
        h.scope.cancel()
    }

    @Test
    fun `a burst offers only the newest entry`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.pollReturnsOnce(
            listOf(
                entry().let { it.copy(id = 1L, dateAddedSeconds = 100L) },
                entry().let { it.copy(id = 2L, dateAddedSeconds = 200L) },
            ),
        )
        h.coordinator.reconcile(prefEnabled = true)
        h.untilOfferPolledOnce()
        // One fixed notification slot: the older entry of the burst is never
        // visible, so it must not cost a probe or a post.
        assertEquals(1, h.offers.size)
        h.scope.cancel()
    }

    @Test
    fun `a suppressed notification channel posts nothing and probes nothing`() = runBlocking {
        val h = Harness(
            permissionGranted = { true },
            posterOverride = object : VoiceNoteOfferPoster {
                override fun canPost() = false
                override fun post(offer: VoiceNoteOffer) {}
            },
        )
        h.pollReturnsOnce(listOf(entry()))
        h.coordinator.reconcile(prefEnabled = true)
        h.untilPolled(1)
        assertTrue(h.offers.isEmpty())
        verify(exactly = 0) { h.preprocessor.getAudioDuration(any()) }
        h.scope.cancel()
    }

    @Test
    fun `an entry without a path posts nothing even when detected`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.pollReturnsOnce(listOf(entry(path = null)))
        h.coordinator.reconcile(prefEnabled = true)
        h.untilPolled(1)
        assertTrue(h.offers.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun `off disposes the detector`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true)
        h.coordinator.reconcile(prefEnabled = false)
        verify(exactly = 1) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `a missing permission keeps the detector unsubscribed`() = runBlocking {
        val h = Harness(permissionGranted = { false })
        h.coordinator.reconcile(prefEnabled = true)
        verify(exactly = 0) { h.detector.subscribe() }
        h.scope.cancel()
    }

    @Test
    fun `rapid toggling re-arms the detector`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.coordinator.reconcile(prefEnabled = true)
        h.coordinator.reconcile(prefEnabled = false)
        h.coordinator.reconcile(prefEnabled = true)
        verify(exactly = 2) { h.detector.subscribe() }
        verify(exactly = 1) { h.detector.dispose() }
        h.scope.cancel()
    }

    @Test
    fun `a permission poke re-evaluates the gate without a preference change`() = runBlocking {
        var granted = false
        val h = Harness(permissionGranted = { granted })
        h.preferences._voiceNoteDetectionEnabled.value = true
        // Before any collector: the permission is missing, so the gate
        // stays closed even with the preference on.
        h.coordinator.reconcile(prefEnabled = true)
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
    fun `start wires the preference flow into the lifecycle`() = runBlocking {
        val h = Harness(permissionGranted = { true })
        h.pollReturnsOnce(listOf(entry()))
        h.coordinator.start()
        // A second start must not double-collect.
        h.coordinator.start()
        h.preferences._voiceNoteDetectionEnabled.value = true
        h.untilOfferPolledOnce()
        verify(atLeast = 1) { h.detector.subscribe() }
        h.preferences._voiceNoteDetectionEnabled.value = false
        // Blocking verify: the collector runs on a real dispatcher.
        verify(timeout = 5_000) { h.detector.dispose() }
        h.scope.cancel()
    }
}
