package com.antivocale.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import com.antivocale.app.data.PerAppPreferencesManager.Companion.WHATSAPP
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.receiver.VoiceNoteIdentityCache
import com.antivocale.app.receiver.VoiceNoteSenderResolution
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TASK-272 (maintainer decision 2026-10-13: the automatic form replaces the
 * semi-automatic offer): one detected voice note becomes one enqueued
 * transcription, zero taps. This class owns the whole hop: resolve the
 * FUSE-hidden WhatsApp path through the tree grant into a sandbox copy,
 * label the sender (TASK-821: the copy is readable, so the house duration
 * probe feeds the identity matcher on the same note that is about to be
 * transcribed; the read is part of the transcription hop the Settings
 * copy declares, and the notification race window lives in
 * [VoiceNoteSenderResolution.matchForDetection]), build the share-shaped
 * service request, hand it to [InferenceEnqueue.start] (the ONE enqueue
 * path; a background start here rides the same API 31+ trampoline
 * fallback the enqueue path owns). Failures are logged, not returned:
 * the caller has no retry affordance (that died with the offer), and the
 * next note re-enters the same path.
 */
@Singleton
class VoiceNoteAutoTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val treeGrant: VoiceNoteTreeGrant,
    private val audioPreprocessor: AudioPreprocessor,
    private val identityCache: VoiceNoteIdentityCache,
) {

    suspend fun enqueue(whatsappPath: String, detectedAtMs: Long) {
        // Runs on the coordinator's IO dispatcher; resolve is suspend all
        // the way down (SAF queries + copy), never a blocking bridge.
        val localPath = treeGrant.resolve(whatsappPath)
        if (localPath == null) {
            Log.i(TAG, "note unresolvable through the grant, skipped: $whatsappPath")
            return
        }
        val senderName = VoiceNoteSenderResolution.matchForDetection(
            identityCache, audioPreprocessor, WHATSAPP, localPath, detectedAtMs)
        when (val outcome = InferenceEnqueue.start(context, serviceIntent(localPath, whatsappPath, senderName))) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> {
                Log.i(TAG, "voice note auto-enqueued: $localPath")
            }
            is InferenceEnqueue.Outcome.Failed -> {
                Log.e(TAG, "voice note enqueue failed", outcome.exception)
            }
        }
    }

    /**
     * The share-shaped request (the offer receiver's contract, moved here
     * when that receiver was removed): request type audio, the LOCAL path,
     * the note-derived task id, the voice-note source so History rows and
     * the result notification can tell the origin apart, and the resolved
     * sender so the row lands in the sender's History group.
     */
    internal fun serviceIntent(localPath: String, whatsappPath: String, senderName: String? = null): Intent =
        Intent(context, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            putExtra(
                TaskerRequestReceiver.EXTRA_TASK_ID,
                // PTT names are unique per note, so the id cannot collide
                // across a burst the way a millisecond timestamp can
                // (review F2: the service drops duplicate taskIds) and a
                // re-resolved note keeps its id.
                "voicenote_${whatsappPath.substringAfterLast('/')}")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, WHATSAPP)
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_VOICE_NOTE)
            senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        }

    private companion object {
        const val TAG = "VoiceNoteAuto"
    }
}
