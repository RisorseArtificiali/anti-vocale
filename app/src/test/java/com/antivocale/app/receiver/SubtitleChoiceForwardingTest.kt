package com.antivocale.app.receiver

import android.content.Intent
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TASK-1018: the subtitle-choice arm is a wholesale passthrough of the
 * choice prompt's extras (SubtitleChoice.baseExtras), with the arm owning
 * exactly two keys (request type, derived track index). This pin guards
 * the wholesale copy: any producer extra rides, including ones no
 * hand-enumerated list knew about, and the arm-owned request type is
 * written AFTER the copy: the debug SPI forward only strips its own
 * bookkeeping keys, so a script can inject request_type into the
 * broadcast, and the user's tap must stay authoritative over it.
 *
 * The prompt carries no task id here on purpose: that path runs the
 * WorkManager fallback cancels, unchanged by TASK-1018 and not part of
 * the extras contract under test (and the project carries no
 * work-testing dependency to stand up WorkManager in-JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SubtitleChoiceForwardingTest {

    @Before
    fun setup() {
        mockkObject(InferenceEnqueue)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `audio arm forwards every prompt extra wholesale and owns request type`() {
        val slot = slot<Intent>()
        every { InferenceEnqueue.start(any(), capture(slot)) } returns
            InferenceEnqueue.Outcome.Started

        val prompt = Intent(NotificationActionReceiver.ACTION_TRANSCRIBE_AUDIO).apply {
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, "/tmp/call.m4a")
            putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, "com.example.recorder")
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_SHARE)
            putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, "external:abc")
            putExtra(InferenceService.EXTRA_CONTENT_HASH, "deadbeef")
            putExtra(InferenceService.EXTRA_SENDER_NAME, "+393331234567")
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
            putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, 99)
            putExtra("spi_junk_key", "rides but is never read")
        }

        NotificationActionReceiver().onReceive(RuntimeEnvironment.getApplication(), prompt)

        verify(exactly = 1) { InferenceEnqueue.start(any(), any()) }
        val service = slot.captured
        assertEquals(InferenceService::class.java.name, service.component?.className)
        assertEquals(
            TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            service.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals("/tmp/call.m4a", service.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertEquals(
            "com.example.recorder",
            service.getStringExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE))
        assertEquals(
            InferenceService.SOURCE_SHARE, service.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(
            "external:abc", service.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
        assertEquals("deadbeef", service.getStringExtra(InferenceService.EXTRA_CONTENT_HASH))
        assertEquals("+393331234567", service.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        // Unknown keys ride inertly: the service reads only known keys.
        assertEquals("rides but is never read", service.getStringExtra("spi_junk_key"))
        // The audio arm owns only the request type; a track index in the
        // prompt rides INERTLY (the orchestrator reads it only on the
        // subtitles branch), exactly as baseExtras always carries it.
        assertEquals(
            99, service.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1))
    }

    @Test
    fun `subtitles arm derives the track index and overrides an injected request type`() {
        val slot = slot<Intent>()
        every { InferenceEnqueue.start(any(), capture(slot)) } returns
            InferenceEnqueue.Outcome.Started

        val prompt = Intent(NotificationActionReceiver.ACTION_USE_SUBTITLES).apply {
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, "/tmp/video.mp4")
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
        }

        NotificationActionReceiver().onReceive(RuntimeEnvironment.getApplication(), prompt)

        val service = slot.captured
        assertEquals(
            TaskerRequestReceiver.REQUEST_TYPE_SUBTITLES,
            service.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        // No track index in the prompt: the derived default is -1, written
        // after the copy.
        assertEquals(
            -1, service.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, 42))
    }
}
