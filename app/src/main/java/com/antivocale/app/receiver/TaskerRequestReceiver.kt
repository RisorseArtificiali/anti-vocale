package com.antivocale.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.BuiltInBackendIds
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * BroadcastReceiver for handling Tasker requests.
 *
 * Supported Intent Actions:
 * - com.antivocale.app.PROCESS_REQUEST
 *
 * Required Extras:
 * - request_type: "text" or "audio"
 * - task_id: Unique identifier for the request
 *
 * Optional Extras:
 * - prompt: Text prompt (required for text requests, optional prefix for audio)
 * - file_path: Path to audio file (required for audio requests)
 *
 * Response Intent:
 * - Action: net.dinglisch.android.tasker.ACTION_TASKER_INTENT
 * - Extras: task_id, status, result_text (on success), error_message (on error)
 *
 * Android 12+ restricts starting foreground services from the background. This receiver
 * tries the direct approach first (works when battery optimization is disabled for the app).
 * If that fails, it posts a notification the user can tap — the tap is user-initiated,
 * satisfying the FGS restriction and allowing the service to start.
 */
@AndroidEntryPoint
class TaskerRequestReceiver : BroadcastReceiver() {

    // TASK-274: the consent gate below reads the preference; this receiver
    // gained injection for exactly that (the ModelPreloadReceiver shape).
    @Inject lateinit var preferencesManager: PreferencesManager

    companion object {
        const val TAG = "TaskerRequestReceiver"

        // Intent actions
        const val ACTION_PROCESS_REQUEST = "com.antivocale.app.PROCESS_REQUEST"
        const val ACTION_TASKER_REPLY = "net.dinglisch.android.tasker.ACTION_TASKER_INTENT"

        // Intent extras
        const val EXTRA_REQUEST_TYPE = "request_type"
        const val EXTRA_PROMPT = "prompt"

        /**
         * The request-type vocabulary every producer must use: the
         * orchestrator dispatches on these values with a silent fallthrough
         * to the text branch, so a typo'd literal compiles clean and
         * "completes" as an empty text transcription.
         */
        const val REQUEST_TYPE_TEXT = "text"
        const val REQUEST_TYPE_AUDIO = "audio"
        const val REQUEST_TYPE_SUBTITLES = "subtitles"

        /** TASK-677 (GH #92 import half): the handed file IS a .srt/.vtt; its
         *  cues become the transcript, no model loads. */
        const val REQUEST_TYPE_SUBTITLE_IMPORT = "subtitle_import"

        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TASK_ID = "task_id"

        /** TASK-394: optional per-request backend/model override (one-shot, not persisted). */
        const val EXTRA_BACKEND_ID = "backend_id"
        const val EXTRA_SUBTITLE_TRACK_INDEX = "subtitle_track_index"

        // Reply extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Status values
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"

        // Fallback notification. The id is a SEQUENTIAL slot in the reserved
        // band (TASK-329 contract), NOT derived from the taskId: nothing ever
        // cancels this notification by a derived id (dismissal is setAutoCancel
        // on tap), so the only property that matters is that two CONCURRENT
        // posts never share a slot. A hash fold cannot promise that (code
        // review 2026-09-03: sequential Tasker ids collide deterministically,
        // e.g. task_8/task_40 band to the same slot at any modulus tried), and
        // the fallback notification's contentIntent is the SOLE carrier of the
        // pending request, so a collision silently dropped a transcription.
        // A counter repeats only after RANGE concurrent notifications.
        internal const val FALLBACK_NOTIFICATION_ID_BASE = 2201
        internal const val FALLBACK_NOTIFICATION_ID_RANGE = 100

        private val fallbackIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun fallbackNotificationId(): Int =
            FALLBACK_NOTIFICATION_ID_BASE + Math.floorMod(
                fallbackIdCounter.getAndIncrement(), FALLBACK_NOTIFICATION_ID_RANGE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROCESS_REQUEST) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }

        Log.i(TAG, "Received Tasker request")

        val requestType = intent.getStringExtra(EXTRA_REQUEST_TYPE) ?: "text"
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "unknown_${System.currentTimeMillis()}"
        // TASK-274: consent gate, BEFORE the allowlist and any enqueue. The
        // automation surface is opt-in (default off); the reply names the
        // setting so an existing Tasker user learns what to flip. The
        // blocking first() reads ONLY the preference cache (the flow emits
        // the cached value onStart, never DataStore): the cache is warm
        // because the Hilt singleton's initialize() runs during THIS
        // receiver's own injection. If initialize() ever turns lazy or
        // async, first() would silently return the constructor default and
        // every enabled user would get fail-closed rejections: revisit this
        // gate with that refactor.
        if (!runBlocking { preferencesManager.externalAutomationEnabled.first() }) {
            Log.w(TAG, "Rejected automation request: external automation is disabled")
            sendTaskerReply(
                context, taskId, STATUS_ERROR,
                errorMessage = context.getString(R.string.external_automation_disabled))
            return
        }
        // TASK-274: path allowlist. The only legitimately transcribable-by-path
        // sources are the app's own staged audio (filesDir/shared_audio) and
        // preprocessing intermediates (cacheDir); every other path already
        // fails unreadable at decode time (device-proven 2026-09-03), so this
        // rejects nothing that ever worked while closing the path-oracle and
        // traversal surface. Canonicalized to defeat '..'.
        if (filePath != null) {
            val canonical = java.io.File(filePath).canonicalFile
            val sharedAudio = java.io.File(context.filesDir, "shared_audio").canonicalFile
            val cache = context.cacheDir.canonicalFile
            val allowed = canonical.path.startsWith(sharedAudio.path + java.io.File.separator) ||
                canonical.path.startsWith(cache.path + java.io.File.separator)
            if (!allowed) {
                Log.w(TAG, "Rejected file_path outside the allowlist: $filePath")
                context.sendBroadcast(Intent(ACTION_TASKER_REPLY).apply {
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_STATUS, STATUS_ERROR)
                    putExtra(EXTRA_ERROR_MESSAGE,
                        "file_path must live under the app's shared_audio or cache directory")
                })
                return
            }
        }
        // TASK-394: one-shot backend override. Unknown ids fail loudly instead of
        // silently falling back to the saved preference (wrong model, looks like success).
        val backendOverride = intent.getStringExtra(EXTRA_BACKEND_ID)
        if (backendOverride != null && !isKnownBackendId(backendOverride)) {
            Log.e(TAG, "Unknown backend_id '$backendOverride'")
            context.sendBroadcast(Intent(ACTION_TASKER_REPLY).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_STATUS, STATUS_ERROR)
                putExtra(EXTRA_ERROR_MESSAGE, "unknown backend_id: $backendOverride")
            })
            return
        }

        Log.d(TAG, "Request: type=$requestType, taskId=$taskId, prompt=${prompt.take(50)}...")

        val serviceIntent = Intent(context, InferenceService::class.java).apply {
            putExtra(EXTRA_REQUEST_TYPE, requestType)
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, backendOverride)
            // TASK-274(f): pin the reply sink. API 34+ names the sending
            // package; the reply broadcast becomes point-to-point instead of
            // world-visible. Below 34 (or system-sent) the field is absent and
            // the service keeps the historical unpinned reply.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                sentFromPackage?.let { putExtra(InferenceService.EXTRA_REQUESTER_PACKAGE, it) }
            }
        }

        // F6: the shared enqueue owns the restriction fallback (trampoline
        // notification preserving the request); this receiver's inline
        // postFallbackNotification was the pattern's birthplace and moved
        // to InferenceEnqueue. Direct start still works when the app is in
        // the foreground or holds the explicit FGS exemption.
        when (val outcome = com.antivocale.app.service.InferenceEnqueue.start(context, serviceIntent)) {
            com.antivocale.app.service.InferenceEnqueue.Outcome.Started ->
                Log.i(TAG, "Started InferenceService directly for taskId: $taskId")
            com.antivocale.app.service.InferenceEnqueue.Outcome.FallbackNotificationPosted ->
                Log.i(TAG, "Posted fallback notification for taskId: $taskId")
            is com.antivocale.app.service.InferenceEnqueue.Outcome.Failed -> {
                Log.e(TAG, "Enqueue failed for taskId: $taskId")
                sendTaskerReply(context, taskId, STATUS_ERROR, errorMessage = "enqueue failed: ${outcome.exception.message}")
            }
        }
    }

    // The registry carries Hilt-scoped constructor state, but this check needs
    // only the static valid-id space, so the ONE shared predicate stays right
    // even now that the receiver has injection (record validity is enforced
    // downstream with a loud ExternalModelUnavailable).
    private fun isKnownBackendId(id: String): Boolean = BuiltInBackendIds.isSelectableBackendId(id)


    /**
     * Sends a reply intent back to Tasker.
     */
    fun sendTaskerReply(
        context: Context,
        taskId: String,
        status: String,
        resultText: String? = null,
        errorMessage: String? = null
    ) {
        val replyIntent = Intent(ACTION_TASKER_REPLY).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_STATUS, status)

            if (status == STATUS_SUCCESS && resultText != null) {
                putExtra(EXTRA_RESULT_TEXT, resultText)
            } else if (status == STATUS_ERROR && errorMessage != null) {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
        }

        Log.d(TAG, "Sending reply: taskId=$taskId, status=$status")
        context.sendBroadcast(replyIntent)
    }
}
