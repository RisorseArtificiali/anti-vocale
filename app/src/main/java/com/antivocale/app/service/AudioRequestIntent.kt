package com.antivocale.app.service

import android.content.Context
import android.content.Intent
import com.antivocale.app.receiver.TaskerRequestReceiver

/**
 * TASK-776: the ONE builder of the identity-carrying audio request sent to
 * [InferenceService]. ShareReceiverActivity (share and subtitle import),
 * ScheduledFolderWorker (folder watch), VoiceNoteAutoTranscriber
 * (automatic voice notes) and LogsViewModel's browse intake each
 * hand-rolled this exact shape; the copies had already begun drifting and
 * the next origin would have diverged silently (a missing extra here is
 * invisible until a row renders without its label).
 *
 * The taskId stays each caller's business: its derivation IS the caller's
 * contract (the PTT-name id keeps a redelivered note from double-running;
 * the folder-watch id folds the tree uri; the share and browse ids are
 * fresh per request).
 *
 * Deliberately NOT composed through this factory, each for a stated
 * reason: the retranscribe class (LogsViewModel.dispatchTranscription,
 * NotificationActionReceiver's rerun-suspended arm) re-runs with a PROMPT
 * and, for the History arm, a language override, beyond this factory's
 * surface; NotificationActionReceiver's subtitle-CHOICE arm forwards the
 * fixed extra list of the choice prompt it answers AND builds the real
 * SUBTITLES request with its track index (the only site allowed to: the
 * index is the user's tap, not a composed value); BenchmarkActivity is
 * the prompt-carrying shape with no identity; the Tasker intake
 * (TaskerRequestReceiver) defaults to the text request type, pins its
 * own reply-sink package extra, and sets no source; TaskerTrampoline-
 * Activity blindly rebroadcasts received extras. Folding any of them in
 * would grow a flags-shaped parameter, the sprawl this extraction exists
 * to avoid.
 */
object AudioRequestIntent {

    fun make(
        context: Context,
        source: String,
        taskId: String,
        filePath: String,
        sourcePackage: String? = null,
        senderName: String? = null,
        contentHash: String? = null,
        backendOverride: String? = null,
        /** TASK-889: the request came from the cold-start catch-up sweep
         *  (the per-note result notification is suppressed for it). */
        catchup: Boolean = false,
    ): Intent = make(
        context, source, taskId, filePath,
        requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
        sourcePackage = sourcePackage,
        senderName = senderName,
        contentHash = contentHash,
        backendOverride = backendOverride,
        catchup = catchup)

    /**
     * Review F1: the subtitle-import arm as its OWN entry point. An open
     * requestType parameter let a caller pass REQUEST_TYPE_SUBTITLES and
     * silently produce a half-built request (no track index); one factory
     * function per request shape keeps the shape unbuildable when its
     * distinguishing extra is missing.
     */
    fun subtitleImport(
        context: Context,
        taskId: String,
        filePath: String,
        sourcePackage: String? = null,
    ): Intent = make(
        context, InferenceService.SOURCE_SHARE, taskId, filePath,
        requestType = TaskerRequestReceiver.REQUEST_TYPE_SUBTITLE_IMPORT,
        sourcePackage = sourcePackage)

    /**
     * TASK-1037 (GH #190) phase 3: the session-resume arm. Carries ONLY the
     * task id and the resume flag: the service reads everything else from
     * the session row (the plan's resume-request shape), so the intent
     * cannot disagree with persisted state, and it rides the API 31+
     * trampoline fallback unchanged (the full-extras forwarding of
     * [TaskerTrampolineActivity]). The session row's frozen identity decides
     * eligibility; [InferenceService] validates it before any work starts.
     */
    fun resumeSession(context: Context, taskId: String): Intent =
        Intent(context, InferenceService::class.java).apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, TaskerRequestReceiver.REQUEST_TYPE_AUDIO)
            putExtra(InferenceService.EXTRA_RESUME_SESSION, true)
        }

    /**
     * TASK-1037 phase 4: the cooperative-pause control request, the ONE
     * builder of the pause intent (History's row button, the debug SPI op
     * and the notification's PendingIntent all deliver this shape; the
     * notification's extra-less variant is [InferenceService]'s own
     * control-action pending intent). A null task id means "the live
     * stint" (the service resolves it); an explicit id must match the
     * live stint or the orchestrator gate refuses. A control delivery:
     * startService, never the enqueue path.
     */
    fun pauseTask(context: Context, taskId: String? = null): Intent =
        Intent(context, InferenceService::class.java).apply {
            action = InferenceService.ACTION_PAUSE_TASK
            taskId?.let { putExtra(InferenceService.EXTRA_PAUSE_TASK_ID, it) }
        }

    private fun make(
        context: Context,
        source: String,
        taskId: String,
        filePath: String,
        requestType: String,
        sourcePackage: String? = null,
        senderName: String? = null,
        contentHash: String? = null,
        backendOverride: String? = null,
        catchup: Boolean = false,
    ): Intent = Intent(context, InferenceService::class.java).apply {
        putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, requestType)
        putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
        putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
        // No prompt extra: the service fills the settings default when the
        // extra is absent (the share path's long-standing contract).
        putExtra(InferenceService.EXTRA_SOURCE, source)
        sourcePackage?.let { putExtra(InferenceService.EXTRA_SOURCE_PACKAGE, it) }
        senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        contentHash?.let { putExtra(InferenceService.EXTRA_CONTENT_HASH, it) }
        backendOverride?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        if (catchup) putExtra(InferenceService.EXTRA_CATCHUP, true)
    }
}
