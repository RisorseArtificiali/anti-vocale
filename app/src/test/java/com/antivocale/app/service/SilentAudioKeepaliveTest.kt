package com.antivocale.app.service

import android.media.AudioTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * GH #67's scoped extraction: the keepalive owns exactly one AudioTrack
 * for its lifetime. These pin the idempotence and release contract (a
 * leaked looping track would hold the "playing media" classification
 * forever: the OEM would never freeze us, and the battery would pay).
 *
 * The AudioTrack.Builder chain is final-class territory on the JVM; the
 * constructor is mocked and every setter answered with the builder so
 * build() hands back the tracked instance.
 */
class SilentAudioKeepaliveTest {

    private lateinit var track: AudioTrack

    @Before
    fun setUp() {
        track = mockk(relaxed = true)
        // The silent contract: write must fill the buffer (a short write
        // fails the start; the relaxed default 0 would trip that check).
        every { track.write(any<ShortArray>(), any(), any()) } answers { thirdArg() }
        mockkConstructor(android.media.AudioAttributes.Builder::class)
        every { anyConstructed<android.media.AudioAttributes.Builder>().setUsage(any()) } answers { self as android.media.AudioAttributes.Builder }
        every { anyConstructed<android.media.AudioAttributes.Builder>().setContentType(any()) } answers { self as android.media.AudioAttributes.Builder }
        every { anyConstructed<android.media.AudioAttributes.Builder>().build() } returns mockk()
        mockkConstructor(android.media.AudioFormat.Builder::class)
        every { anyConstructed<android.media.AudioFormat.Builder>().setEncoding(any()) } answers { self as android.media.AudioFormat.Builder }
        every { anyConstructed<android.media.AudioFormat.Builder>().setSampleRate(any()) } answers { self as android.media.AudioFormat.Builder }
        every { anyConstructed<android.media.AudioFormat.Builder>().setChannelMask(any()) } answers { self as android.media.AudioFormat.Builder }
        every { anyConstructed<android.media.AudioFormat.Builder>().build() } returns mockk()
        mockkConstructor(android.media.AudioTrack.Builder::class)
        every { anyConstructed<android.media.AudioTrack.Builder>().setAudioAttributes(any()) } answers { self as android.media.AudioTrack.Builder }
        every { anyConstructed<android.media.AudioTrack.Builder>().setAudioFormat(any()) } answers { self as android.media.AudioTrack.Builder }
        every { anyConstructed<android.media.AudioTrack.Builder>().setTransferMode(any<Int>()) } answers { self as android.media.AudioTrack.Builder }
        every { anyConstructed<android.media.AudioTrack.Builder>().setBufferSizeInBytes(any()) } answers { self as android.media.AudioTrack.Builder }
        every { anyConstructed<android.media.AudioTrack.Builder>().build() } returns track
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `start plays exactly once when called twice`() {
        val ka = SilentAudioKeepalive("test")
        ka.start()
        ka.start()
        verify(exactly = 1) { track.play() }
        verify(exactly = 1) { track.setLoopPoints(any(), any(), any()) }
    }

    @Test
    fun `stop halts and releases the track and is idempotent`() {
        val ka = SilentAudioKeepalive("test")
        ka.start()
        ka.stop()
        ka.stop()
        verify(exactly = 1) { track.stop() }
        verify(exactly = 1) { track.release() }
    }

    @Test
    fun `a throwing play releases the built track and start swallows`() {
        every { track.play() } throws IllegalStateException("audio path broken")
        val ka = SilentAudioKeepalive("test")
        ka.start() // must not throw
        verify(exactly = 1) { track.release() }
        // retryable: a second start builds again and may succeed
        every { track.play() } returns Unit
        ka.start()
        verify(atLeast = 1) { track.play() }
    }

    @Test
    fun `stop without start is a no-op`() {
        val ka = SilentAudioKeepalive("test")
        ka.stop()
        verify(exactly = 0) { track.release() }
    }

    @Test
    fun `a short write fails the start and releases the track`() {
        // The silent contract: a partially filled static buffer would loop
        // garbage. The start must fail, release the built track (native
        // resources + audio-session entry), and never reach play/loop.
        every { track.write(any<ShortArray>(), any(), any()) } returns 100
        val ka = SilentAudioKeepalive("test")
        ka.start() // must not throw
        verify(exactly = 1) { track.release() }
        verify(exactly = 0) { track.play() }
        verify(exactly = 0) { track.setLoopPoints(any(), any(), any()) }
    }
}
