package com.antivocale.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.receiver.TaskerRequestReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-776: the shared audio-request factory's contract, pinned at the
 * factory itself. The voice-note arm is additionally pinned end to end by
 * VoiceNoteAutoTranscriberTest ("mirrors the share path's audio shape"),
 * which drove the extraction: these tests own the arms no other suite
 * covers (share audio, the subtitles variant, subtitle import).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRequestIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `audio requests carry the core shape and no prompt`() {
        val intent = AudioRequestIntent.make(
            context, InferenceService.SOURCE_SHARE, "t1", "/local/a.wav")
        assertEquals(TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
            intent.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals("/local/a.wav", intent.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertEquals("t1", intent.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID))
        assertEquals(InferenceService.SOURCE_SHARE, intent.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(InferenceService::class.java.name, intent.component?.className)
        // The share path's long-standing contract: NO prompt extra, the
        // service fills the settings default.
        assertNull(intent.getStringExtra(TaskerRequestReceiver.EXTRA_PROMPT))
        assertEquals(-1, intent.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1))
    }

    @Test
    fun `the subtitle import entry point carries its type and source, never a track index`() {
        val anImport = AudioRequestIntent.subtitleImport(context, "t3", "/local/s.srt")
        assertEquals(
            TaskerRequestReceiver.REQUEST_TYPE_SUBTITLE_IMPORT,
            anImport.getStringExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE))
        assertEquals(InferenceService.SOURCE_SHARE, anImport.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(
            "the factory never composes a subtitle track index (the one site allowed to build SUBTITLES is the subtitle-choice tap arm in NotificationActionReceiver, where the index is the user's tap)",
            -1, anImport.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1))
    }

    @Test
    fun `the browse arm keeps its fresh id and source`() {
        val browse = AudioRequestIntent.make(
            context, InferenceService.SOURCE_BROWSE,
            java.util.UUID.randomUUID().toString(), "/local/pick.wav")
        assertEquals(InferenceService.SOURCE_BROWSE, browse.getStringExtra(InferenceService.EXTRA_SOURCE))
        assertEquals(InferenceService::class.java.name, browse.component?.className)
        // A regression to a stable taskId would be silently dropped by the
        // service's in-flight dedup: the pin is that the id is caller-supplied
        // and per-request (review F5; SOURCE_BROWSE appears in no other suite).
        assertEquals(36, browse.getStringExtra(TaskerRequestReceiver.EXTRA_TASK_ID)!!.length)
    }

    @Test
    fun `optional identity extras ride when present and stay absent otherwise`() {
        val full = AudioRequestIntent.make(
            context, InferenceService.SOURCE_SHARE, "t4", "/local/b.opus",
            sourcePackage = "com.whatsapp",
            senderName = "Anna",
            contentHash = "abc123",
            backendOverride = "whisper")
        assertEquals("com.whatsapp", full.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
        assertEquals("Anna", full.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertEquals("abc123", full.getStringExtra(InferenceService.EXTRA_CONTENT_HASH))
        assertEquals("whisper", full.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))

        val bare = AudioRequestIntent.make(
            context, InferenceService.SOURCE_FOLDER_WATCH, "t5", "/local/c.opus")
        assertEquals(InferenceService::class.java.name, bare.component?.className)
        assertNull(bare.getStringExtra(InferenceService.EXTRA_SOURCE_PACKAGE))
        assertNull(bare.getStringExtra(InferenceService.EXTRA_SENDER_NAME))
        assertNull(bare.getStringExtra(InferenceService.EXTRA_CONTENT_HASH))
        assertNull(bare.getStringExtra(InferenceService.EXTRA_BACKEND_OVERRIDE))
    }

    /**
     * TASK-889: only the cold-start sweep sets the catch-up flag; every
     * live detection leaves it off, so the default arm must stay silent
     * (a stray true would suppress the zero-tap result notification).
     */
    @Test
    fun `the catch-up flag rides only when the sweep sets it`() {
        val sweep = AudioRequestIntent.make(
            context, InferenceService.SOURCE_VOICE_NOTE, "t6", "/local/d.opus", catchup = true)
        assertEquals(true, sweep.getBooleanExtra(InferenceService.EXTRA_CATCHUP, false))

        val live = AudioRequestIntent.make(
            context, InferenceService.SOURCE_VOICE_NOTE, "t7", "/local/e.opus")
        assertEquals(false, live.getBooleanExtra(InferenceService.EXTRA_CATCHUP, false))
    }
}
