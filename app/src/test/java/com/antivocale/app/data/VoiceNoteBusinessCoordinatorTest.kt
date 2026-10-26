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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WhatsApp Business support: the two per-source coordinators side by side
 * over ONE preference store, the production shape (AppModule builds both
 * against the same PreferencesManager). Each must read only its own key
 * pair, arm and dispose only its own detector, and hand its own source to
 * the auto-transcriber.
 */
class VoiceNoteBusinessCoordinatorTest {

    private val preferences = FakePreferencesManager()
    private val treeGrant = mockk<VoiceNoteTreeGrant>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val enqueued = mutableListOf<Pair<String, VoiceNoteSource>>()
    private val autoTranscriber = mockk<VoiceNoteAutoTranscriber> {
        coEvery { enqueue(any(), any(), any(), any(), any()) } coAnswers {
            synchronized(enqueued) { enqueued += firstArg<String>() to arg<VoiceNoteSource>(3) }
        }
    }
    private val detections = VoiceNoteSource.entries.associateWith {
        MutableSharedFlow<VoiceNoteEntry>(extraBufferCapacity = 8)
    }
    private val detectors = VoiceNoteSource.entries.associateWith { source ->
        mockk<VoiceNoteDetector> {
            every { this@mockk.detections } returns this@VoiceNoteBusinessCoordinatorTest.detections.getValue(source)
            every { catchUpBursts } returns MutableSharedFlow()
            every { subscribe() } just Runs
            every { dispose() } just Runs
            every { refresh() } just Runs
        }
    }
    private val coordinators = VoiceNoteDetectionCoordinators(
        VoiceNoteSource.entries.associateWith { source ->
            VoiceNoteDetectionCoordinator(
                detectors.getValue(source),
                // TASK-889: the digest post target; never fires in these
                // lifecycle tests.
                mockk<android.content.Context>(relaxed = true),
                preferences, treeGrant, autoTranscriber,
                VoiceNotePermissionChecker { true }, scope, source)
        })

    private val whatsApp get() = coordinators.forSource(VoiceNoteSource.WHATSAPP)
    private val business get() = coordinators.forSource(VoiceNoteSource.WHATSAPP_BUSINESS)

    init {
        every { treeGrant.holdsGrant(any()) } returns true
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `the holder requires exactly one coordinator per source`() {
        val only = VoiceNoteDetectionCoordinator(
            detectors.getValue(VoiceNoteSource.WHATSAPP),
            mockk<android.content.Context>(relaxed = true),
            preferences, treeGrant, autoTranscriber,
            VoiceNotePermissionChecker { true }, scope)
        val failure = runCatching { VoiceNoteDetectionCoordinators(mapOf(VoiceNoteSource.WHATSAPP to only)) }
        assertTrue("a missing source must fail at construction", failure.isFailure)
        assertEquals(VoiceNoteSource.entries.size, coordinators.all.size)
    }

    @Test
    fun `enabling Business arms only the Business detector`() = runBlocking {
        preferences._voiceNoteBusinessTreeUri.value = "content://tree/w4b"
        coordinators.start()
        preferences._voiceNoteBusinessDetectionEnabled.value = true
        until { business.armed }
        verify(exactly = 1) { detectors.getValue(VoiceNoteSource.WHATSAPP_BUSINESS).subscribe() }
        verify(exactly = 0) { detectors.getValue(VoiceNoteSource.WHATSAPP).subscribe() }
        assertFalse("the WhatsApp gate must stay closed", whatsApp.armed)
        assertEquals(VoiceNoteGateState.Inactive, whatsApp.gateState.value)
        assertEquals(VoiceNoteGateState.Active, business.gateState.value)
    }

    @Test
    fun `each source gates on its own folder grant`() = runBlocking {
        // Both toggles on, only the WhatsApp folder granted: Business must
        // report the missing grant instead of borrowing the other tree.
        preferences._voiceNoteDetectionEnabled.value = true
        preferences._voiceNoteBusinessDetectionEnabled.value = true
        preferences._voiceNoteTreeUri.value = "content://tree/wa"
        coordinators.start()
        until { whatsApp.armed }
        until { business.gateState.value == VoiceNoteGateState.GrantMissing }
        assertFalse(business.armed)
    }

    @Test
    fun `turning Business off disposes only its detector`() = runBlocking {
        preferences._voiceNoteDetectionEnabled.value = true
        preferences._voiceNoteTreeUri.value = "content://tree/wa"
        preferences._voiceNoteBusinessDetectionEnabled.value = true
        preferences._voiceNoteBusinessTreeUri.value = "content://tree/w4b"
        coordinators.start()
        until { whatsApp.armed && business.armed }

        preferences._voiceNoteBusinessDetectionEnabled.value = false
        until { !business.armed }
        // The dispose itself hops to Dispatchers.IO after `armed` flips, so
        // under full-suite load the flag can be observed before the dispose
        // lands: poll the mock, not the flag, before verifying.
        until { io.mockk.MockKVerificationScope::class.let { true } &&
            runCatching {
                io.mockk.verify(atLeast = 1) {
                    detectors.getValue(VoiceNoteSource.WHATSAPP_BUSINESS).dispose()
                }
            }.isSuccess }
        verify(exactly = 1) { detectors.getValue(VoiceNoteSource.WHATSAPP_BUSINESS).dispose() }
        verify(exactly = 0) { detectors.getValue(VoiceNoteSource.WHATSAPP).dispose() }
        assertTrue("WhatsApp detection must survive the Business toggle", whatsApp.armed)
    }

    @Test
    fun `each coordinator enqueues with its own source and live uri`() = runBlocking {
        preferences._voiceNoteDetectionEnabled.value = true
        preferences._voiceNoteTreeUri.value = "content://tree/wa"
        preferences._voiceNoteBusinessDetectionEnabled.value = true
        preferences._voiceNoteBusinessTreeUri.value = "content://tree/w4b"
        coordinators.start()
        until { whatsApp.armed && business.armed }

        // The same PTT name from both clients: two different notes.
        val name = "PTT-20261020-WA0001.opus"
        until {
            detections.getValue(VoiceNoteSource.WHATSAPP)
                .tryEmit(VoiceNoteEntry(1L, "/wa/202642/$name", 1_000L))
            detections.getValue(VoiceNoteSource.WHATSAPP_BUSINESS)
                .tryEmit(VoiceNoteEntry(2L, "/w4b/202642/$name", 1_000L))
            synchronized(enqueued) { enqueued.map { it.second }.toSet().size == 2 }
        }
        val bySource = synchronized(enqueued) { enqueued.associate { (path, source) -> source to path } }
        assertEquals("/wa/202642/$name", bySource[VoiceNoteSource.WHATSAPP])
        assertEquals("/w4b/202642/$name", bySource[VoiceNoteSource.WHATSAPP_BUSINESS])
    }

    @Test
    fun `a permission poke reaches every source`() = runBlocking {
        var granted = false
        val gated = VoiceNoteDetectionCoordinators(
            VoiceNoteSource.entries.associateWith { source ->
                VoiceNoteDetectionCoordinator(
                    detectors.getValue(source),
                    mockk<android.content.Context>(relaxed = true),
                    preferences, treeGrant, autoTranscriber,
                    VoiceNotePermissionChecker { granted }, scope, source)
            })
        preferences._voiceNoteDetectionEnabled.value = true
        preferences._voiceNoteTreeUri.value = "content://tree/wa"
        preferences._voiceNoteBusinessDetectionEnabled.value = true
        preferences._voiceNoteBusinessTreeUri.value = "content://tree/w4b"
        gated.start()
        until { gated.all.all { it.gateState.value == VoiceNoteGateState.PermissionMissing } }

        granted = true
        gated.pokePermissionState()
        until { gated.all.all { it.armed } }
    }
}
