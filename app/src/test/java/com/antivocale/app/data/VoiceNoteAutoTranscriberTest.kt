package com.antivocale.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.receiver.VoiceNoteIdentityCache
import com.antivocale.app.service.InferenceService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TASK-272 (the automatic form): the auto-transcriber's JVM-reachable
 * contract, ported from the deleted offer receiver's test. The
 * service-intent shape (the share path's mirror) and the unresolvable-note
 * early return; the resolved enqueue leg is device territory (the
 * 2026-10-13 trial proved the chain end to end). TASK-821 adds the sender
 * extra; the match-and-race-window policy is pinned in
 * VoiceNoteSenderResolutionTest, next to its siblings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteAutoTranscriberTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val resolvedUris = mutableListOf<String?>()

    private fun impl(
        resolveResult: String?,
        alreadyTranscribed: Boolean = false,
    ) = VoiceNoteAutoTranscriber(
        context,
        mockk<VoiceNoteTreeGrant> {
            coEvery { resolve(any(), any()) } coAnswers {
                resolvedUris += firstArg<String?>()
                resolveResult
            }
        },
        mockk<AudioPreprocessor>(),
        VoiceNoteIdentityCache(),
        mockk<VoiceNoteDuplicateGuard> {
            coEvery { alreadyTranscribed(any()) } returns alreadyTranscribed
        },
    )

    @Test
    fun `enqueue forwards the live tree uri into resolve`() = kotlinx.coroutines.runBlocking {
        // TASK-832 review F1: the middle hop of the three-hop chain. The
        // coordinator pins its own hand-off; this pins enqueue's.
        impl(resolveResult = null)
            .enqueue("/w/PTT-1.opus", 1_000L, treeUri = "content://tree/live")
        assertEquals(listOf<String?>("content://tree/live"), resolvedUris)
    }

    @Test
    fun `serviceIntent mirrors the share path's audio shape`() {
        val serviceIntent = impl("/local/n.opus")
            .serviceIntent("/local/n.opus", "/tree/202642/PTT-1.opus")
        assertEquals(
            TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals(
            "the service must receive the RESOLVED sandbox path",
            "/local/n.opus", serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        // The task id derives from the note's unique PTT name (millisecond
        // ids can collide across a burst; review F2).
        assertEquals(
            "voicenote_PTT-1.opus",
            serviceIntent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID))
        assertEquals("com.whatsapp", serviceIntent.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
        assertEquals(InferenceService.SOURCE_VOICE_NOTE, serviceIntent.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(InferenceService::class.java.name, serviceIntent.component?.className)
    }

    @Test
    fun `a labeled note carries the sender, an unlabeled one carries no extra`() {
        val impl = impl("/local/n.opus")
        assertEquals(
            "Anna",
            impl.serviceIntent("/local/n.opus", "/w/PTT-1.opus", senderName = "Anna")
                .getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertNull(
            impl.serviceIntent("/local/n.opus", "/w/PTT-1.opus")
                .getStringExtra(InferenceService.EXTRA_SENDER_NAME))
    }

    @Test
    fun `a note already transcribed is silently deduped`() = runBlocking {
        // TASK-842: WhatsApp re-delivering a completed note (redownload,
        // folder scan) must not re-run the transcription; the guard skips
        // BEFORE the grant resolve, so an already-transcribed note costs
        // nothing.
        impl(resolveResult = "/local/n.opus", alreadyTranscribed = true)
            .enqueue("/w/PTT-1.opus", 1_000L, treeUri = "content://tree/test")
        assertEquals(
            "no service may start for a deduped note",
            null,
            shadowOf(context.applicationContext as android.app.Application).nextStartedService)
    }

    @Test
    fun `an unresolvable note enqueues nothing`() = runBlocking {
        // resolve returns null (no grant, folder moved, file deleted): the
        // honest skip, and nothing may start.
        impl(resolveResult = null).enqueue("/gone/PTT-1.opus", 1_000L, treeUri = "content://tree/test")
        assertEquals(
            "no service may start for an unresolvable note",
            null,
            shadowOf(context.applicationContext as android.app.Application).nextStartedService)
    }
}
