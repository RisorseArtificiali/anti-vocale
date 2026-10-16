package com.antivocale.app.ui.components

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.antivocale.app.transcription.TimedSegment
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TASK-850: the ResultPlaybackController state machine, driven through the
 * two seams (player factory + focus), plain JVM + setMain(Unconfined) +
 * runBlocking real time (the SettingsViewModelBackupTest precedent; the
 * runTest + setMain combo live-locks this class: kotlinx's
 * TestMainDispatcher concurrent-access detector spins forever once the
 * controller's scope dispatches during runCurrent - jstack-verified, 58s
 * of CPU in fillInStackTrace). Every android.media METHOD the controller
 * calls is overridden by the fakes below; the FakeMediaPlayer CONSTRUCTOR
 * still touches the android.jar stub and relies on
 * unitTests.isReturnDefaultValues = true (flipping that flag kills the
 * class with a Stub! error). The lifecycle rules under test: the identity
 * guard, teardown-on-every-exit (with focus abandoned), the
 * wantsPlayback prepare-race, the paused-setSpeed re-pause OEM guard, the
 * speed lifetime, and the seek ticker's latch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultPlaybackControllerTest {

    private lateinit var focus: FakeFocus
    private val players = mutableListOf<FakeMediaPlayer>()
    private lateinit var controller: ResultPlaybackController
    private var factoryThrows = false

    /** The focus seam fake: records grants/abandons, can fire a loss. */
    private class FakeFocus : AudioFocus {
        var grants = 0
        var abandons = 0
        private var onLoss: (() -> Unit)? = null
        var refuse = false

        override fun request(onLoss: () -> Unit): FocusHandle? {
            if (refuse) return null
            grants++
            this.onLoss = onLoss
            return FocusHandle { abandons++ }
        }

        fun fireLoss() = onLoss?.invoke()
    }

    /**
     * A real MediaPlayer subclass with an event log: the ordering
     * assertions (setSpeed before the OEM re-pause) read the log, not a
     * mock framework's verifyOrder.
     */
    private class FakeMediaPlayer : MediaPlayer() {
        var preparedListener: OnPreparedListener? = null
        var completionListener: OnCompletionListener? = null
        var errorListener: OnErrorListener? = null
        // The test thread writes this while the ticker reads it.
        @Volatile
        var position = 0
        var failParams = false
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun setOnPreparedListener(listener: OnPreparedListener?) {
            preparedListener = listener
        }

        override fun setOnCompletionListener(listener: OnCompletionListener?) {
            completionListener = listener
        }

        override fun setOnErrorListener(listener: OnErrorListener?) {
            errorListener = listener
        }

        // The shadow's state machine is not initialized in these tests
        // (prepared fires by hand); the configuration calls no-op.
        override fun setAudioAttributes(attributes: android.media.AudioAttributes?) {}

        override fun setDataSource(path: String?) {}

        override fun prepareAsync() {}

        override fun start() {
            events.add("start")
        }

        override fun pause() {
            events.add("pause")
        }

        override fun release() {
            events.add("release")
        }

        override fun seekTo(msec: Int) {
            events.add("seekTo:$msec")
        }

        override fun getCurrentPosition(): Int = position

        override fun getDuration(): Int = 10_000

        // The params chain must round-trip: the plain-JVM stub's
        // setSpeed returns null, so the fake hands out a mocked instance
        // whose chain returns itself (mockk-inline is fine on plain JVM;
        // it only asserts under Robolectric's classloader).
        private val params: PlaybackParams = io.mockk.mockk()
        override fun getPlaybackParams(): PlaybackParams = params

        init {
            io.mockk.every { params.setSpeed(any()) } returns params
        }

        override fun setPlaybackParams(params: PlaybackParams) {
            if (failParams) throw IllegalStateException("error state")
            events.add("params")
        }
    }

    @Before
    fun setup() {
        // Unconfined: the controller's scope launches run eagerly and the
        // ticker's real-time delay(200) resumes on the default executor,
        // which is exactly what these tests need (see the class KDoc).
        Dispatchers.setMain(Dispatchers.Unconfined)
        focus = FakeFocus()
        controller = ResultPlaybackController(
            audioFocus = focus,
            playerFactory = {
                if (factoryThrows) error("platform broke")
                FakeMediaPlayer().also { players.add(it) }
            },
        )
    }

    @After
    fun tearDown() {
        // Belt and braces: tests that already released are unaffected.
        controller.release()
        Dispatchers.resetMain()
    }

    private fun audioFile(): String =
        Files.createTempFile("playback", ".opus").toFile().absolutePath

    private fun cue(start: Long, end: Long) = TimedSegment(startMs = start, endMs = end, text = "t")

    @Test
    fun `prepare starts playback and reports duration`() = runBlocking {
        controller.toggle("a", audioFile())
        val p = players.single()
        assertEquals(false, controller.state.value?.playing)
        p.preparedListener!!.onPrepared(p)
        assertEquals(true, controller.state.value?.playing)
        assertEquals(10_000, controller.state.value?.durationMs)
        assertEquals(listOf("params", "start"), p.events)
        controller.release()
    }

    @Test
    fun `identity guard ignores a prepared event from the switched-away player`() = runBlocking {
        controller.toggle("a", audioFile())
        val a = players[0]
        controller.toggle("b", audioFile())
        val b = players[1]
        // A's prepare completed AFTER the switch to B: the corpse must not
        // start, and B's pending state stands.
        a.preparedListener!!.onPrepared(a)
        assertFalse("the corpse must not start", a.events.contains("start"))
        assertTrue("switched-away player released", a.events.contains("release"))
        assertEquals("b", controller.state.value?.taskId)
        assertEquals(false, controller.state.value?.playing)
        b.preparedListener!!.onPrepared(b)
        assertEquals(true, controller.state.value?.playing)
        controller.release()
    }

    @Test
    fun `a focus loss during prepare is honored at prepared`() = runBlocking {
        val file = audioFile()
        controller.toggle("a", file)
        val p = players.single()
        focus.fireLoss()  // pause() lands while prepareAsync is in flight
        assertEquals(false, controller.state.value?.playing)
        p.preparedListener!!.onPrepared(p)
        assertFalse("no auto-start against the user's pause", p.events.contains("start"))
        // The resume path starts the prepared player later.
        controller.toggle("a", file)
        assertTrue(p.events.contains("start"))
        assertEquals(true, controller.state.value?.playing)
        controller.release()
    }

    @Test
    fun `completion tears down and abandons focus`() = runBlocking {
        controller.toggle("a", audioFile())
        val p = players.single()
        p.preparedListener!!.onPrepared(p)
        p.completionListener!!.onCompletion(p)
        assertNull(controller.state.value)
        assertTrue(p.events.contains("release"))
        assertEquals("focus abandoned on completion", 1, focus.abandons)
    }

    @Test
    fun `an async prepare error tears down and abandons focus`() = runBlocking {
        controller.toggle("a", audioFile())
        val p = players.single()
        assertTrue(p.errorListener!!.onError(p, 1, 1))
        assertNull(controller.state.value)
        assertTrue(p.events.contains("release"))
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a refused focus grant means no playback at all`() = runBlocking {
        focus.refuse = true
        controller.toggle("a", audioFile())
                assertNull(controller.state.value)
        assertTrue("no player created", players.isEmpty())
    }

    @Test
    fun `a broken player construction tears down and abandons`() = runBlocking {
        factoryThrows = true
        controller.toggle("a", audioFile())
                assertNull(controller.state.value)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `cycleSpeed re-pauses when paused and never lies about a failed apply`() = runBlocking {
        val file = audioFile()
        controller.toggle("a", file)
        val p = players.single()
        p.preparedListener!!.onPrepared(p)
        // Pause, then cycle: setSpeed FIRST, then the OEM re-pause.
        controller.toggle("a", file)
        controller.cycleSpeed()
        assertEquals("params apply before the re-pause",
            listOf("params", "pause"), p.events.takeLast(2))
        assertEquals(1.25f, controller.state.value?.speed)
        // A failed apply must not flip the reported rate.
        p.failParams = true
        controller.cycleSpeed()
        assertEquals("no phantom 1.5x", 1.25f, controller.state.value?.speed)
        controller.release()
    }

    @Test
    fun `the speed survives a row switch and rides the next prepare`() = runBlocking {
        val file = audioFile()
        controller.toggle("a", file)
        val a = players[0]
        a.preparedListener!!.onPrepared(a)
        controller.cycleSpeed()   // 1.25
        controller.cycleSpeed()   // 1.5
        controller.toggle("b", audioFile())
        val b = players[1]
        b.preparedListener!!.onPrepared(b)
        assertEquals(1.5f, controller.state.value?.speed)
        assertEquals(listOf("params", "start"), b.events)
        controller.release()
    }

    @Test
    fun `the seek ticker skips a gap once and latches the pending seek`() = runBlocking {
        val file = audioFile()
        controller.toggle("a", file, listOf(cue(0, 1_000), cue(6_000, 8_000)))
        val p = players.single()
        p.preparedListener!!.onPrepared(p)
        controller.toggleSkipSilences()
        p.position = 3_000
        delay(300)
        assertEquals(
            "state=${controller.state.value} skip=${controller.skipSilences.value} events=${p.events}",
            listOf("seekTo:6000"), p.events.filter { it.startsWith("seekTo") })
        controller.release()
    }

    @Test
    fun `release tears down everything`() = runBlocking {
        controller.toggle("a", audioFile())
        val p = players.single()
        p.preparedListener!!.onPrepared(p)
        controller.release()
        assertNull(controller.state.value)
        assertTrue(p.events.contains("release"))
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a vanished file neither creates a player nor takes focus`() = runBlocking {
        controller.toggle("a", "/nonexistent/path.opus")
                assertNull(controller.state.value)
        assertTrue(players.isEmpty())
        assertEquals(0, focus.grants)
    }

    @Test
    fun `playbackFileExists is the honest sweep gate`() {
        val f = audioFile()
        assertTrue(playbackFileExists(f))
        assertFalse(playbackFileExists(null))
        assertFalse(playbackFileExists("$f.gone"))
    }
}
