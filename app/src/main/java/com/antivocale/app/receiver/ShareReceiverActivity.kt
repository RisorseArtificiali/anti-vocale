package com.antivocale.app.receiver

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.receiver.ChooserBroadcastReceiver
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.SharedAudioHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * Transparent activity for receiving shared audio files.
 * Handles ACTION_SEND intents with audio MIME types from other apps.
 *
 * Now includes source app detection for per-app notification preferences, and a subtitle
 * probe branch: when the shared file is a video containing readable text subtitle tracks,
 * the user is offered a choice (use subtitles vs. transcribe audio) via a notification
 * instead of starting ASR immediately.
 */
/**
 * Hilt entry point for fetching [PreferencesManager] without annotating this transparent
 * share-target Activity with @AndroidEntryPoint (which requires a ComponentActivity subclass).
 * Used only to read the transcription-language preference for subtitle track selection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SubtitlePrefsEntryPoint {
    val preferencesManager: PreferencesManager
}

/**
 * Hilt entry point for fetching [BackendRegistry] in the same no-@AndroidEntryPoint
 * situation as [SubtitlePrefsEntryPoint]: the registry derives dynamic external-model
 * descriptors from the store, so callers must resolve the app-wide singleton rather
 * than constructing their own instance.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackendRegistryEntryPoint {
    fun backendRegistry(): BackendRegistry
    /** The chooser reads valid external records; same no-@AndroidEntryPoint situation. */
    fun externalModelStore(): com.antivocale.app.data.ExternalModelStore

    /**
     * GH #18: the share-time decode probe. The copy path accepts every file,
     * so this is how the share flow learns whether the device can actually
     * decode the container before dispatching transcription.
     */
    fun audioPreprocessor(): com.antivocale.app.audio.AudioPreprocessor

    /**
     * The process-lifetime scope (TASK-438 rule: no hand-built scopes). The
     * share flow's copy-and-dispatch work must run to completion even after
     * this activity finishes, so it outlives the activity by design.
     */
    @com.antivocale.app.di.ApplicationScope
    fun applicationScope(): CoroutineScope
}

/**
 * Transparent activity for receiving shared audio files.
 * Handles ACTION_SEND intents with audio MIME types from other apps.
 *
 * Now includes source app detection for per-app notification preferences, and a subtitle
 * probe branch: when the shared file is a video containing readable text subtitle tracks,
 * the user is offered a choice (use subtitles vs. transcribe audio) via a notification
 * instead of starting ASR immediately.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        const val TAG = "ShareReceiverActivity"
        const val EXTRA_SOURCE_PACKAGE = "source_package"

        /**
         * Marker set by [com.antivocale.app.data.ShareShortcutManager] on its
         * dynamic launcher-shortcut intents. A shortcut intent carries the
         * ACTION_SEND alias component but cannot carry EXTRA_STREAM, so with
         * this marker the null-stream case opens the SAF audio picker instead
         * of the "no audio" error path. Without it, every null-stream intent
         * keeps the legacy error behavior.
         */
        const val EXTRA_FROM_SHORTCUT = "com.antivocale.app.FROM_SHORTCUT"

        // The choice prompt auto-resolves to ASR after this delay if the user does nothing.
        // Keeps a shared video from silently hanging when the notification is ignored.

        // Request code of the shortcut flow's SAF audio pick ([launchAudioPicker]).
        private const val REQUEST_PICK_AUDIO = 1

        // Saved-state stamp written by [onSaveInstanceState] once the
        // copy-and-dispatch coroutine has started: the pid of the process
        // that started it; see the recreation guard in onCreate.
        private const val STATE_DISPATCH_PID = "dispatch_pid"

        // Reserved-range contract (TASK-440): the subtitle-choice prompt and
        // the share-error notification each own a SUB-BAND of the 2401..2500
        // range, so a raw hashCode can never land in the result allocator's
        // range or on any other fixed/banded id, AND a share error can never
        // replace a pending choice prompt (review 2026-09-03: two hash
        // domains folded into one band collided with p=1/100, and the timeout
        // worker's cancel then killed whichever notification held the slot).
        // Internal so the contract test can pin the bands' derived tops via
        // live constants.
        internal const val NOTIFICATION_ID_BAND_BASE = 2401
        internal const val NOTIFICATION_ID_BAND_RANGE = 100
        internal const val CHOICE_ID_BAND_BASE = 2401
        internal const val CHOICE_ID_BAND_RANGE = 50
        internal const val ERROR_ID_BAND_BASE = 2451
        internal const val ERROR_ID_BAND_RANGE = 50

        // Stable notification id per taskId so the choice prompt can be cancelled by the
        // tap receiver or replaced on a re-share of the same taskId. SubtitleChoiceTimeoutWorker
        // and NotificationActionReceiver cancel through this same derivation, so same
        // taskId must keep mapping to the same id.
        internal fun choiceNotificationId(taskId: String): Int =
            ResultNotificationFactory.bandedNotificationId(
                taskId.hashCode(), CHOICE_ID_BAND_BASE, CHOICE_ID_BAND_RANGE
            )

        // Stable per error message: repeating the same failure replaces its
        // notification instead of stacking duplicates. Its own sub-band, so an
        // error can never replace a pending choice prompt.
        internal fun errorNotificationId(message: String): Int =
            ResultNotificationFactory.bandedNotificationId(
                message.hashCode(), ERROR_ID_BAND_BASE, ERROR_ID_BAND_RANGE
            )

        // The registry is NOT held here. Only DI assembles the store+provider pair this
        // registry needs; a second hand-built instance would add a second records collector
        // and split store mutations across racing read-modify-write domains. Callers resolve
        // the app singleton via [BackendRegistryEntryPoint] and pass it in.
        // The ShareExternal family alias (single source: ShareTargetManager) is resolved
        // to a SENTINEL here; the instance flow replaces it with a concrete external:<id>.
        internal const val EXTERNAL_FAMILY_BACKEND_ID = "external"

        internal fun backendIdForAlias(aliasClassName: String, registry: BackendRegistry): String? =
            if (aliasClassName == com.antivocale.app.data.ShareTargetManager.EXTERNAL_FAMILY_ALIAS) EXTERNAL_FAMILY_BACKEND_ID
            else registry.byShareAlias(aliasClassName)?.backendId
    }

    private var sourcePackage: String? = null
    private var detectionTimeoutHandler: Handler? = null
    private var detectionTimeoutRunnable: Runnable? = null

    /** Set when the copy-and-dispatch coroutine starts; the only state the
     *  recreation guard needs (see [onSaveInstanceState]). */
    private var dispatchStarted = false

    /** The app-wide entry point, resolved once per instance; [appScope] and
     *  the registry/store reads all derive from it. */
    private val appEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, BackendRegistryEntryPoint::class.java)
    }

    /** The process-lifetime scope (TASK-438 rule: no hand-built scopes). The
     *  share flow must run to completion even after finish(). */
    private val appScope: CoroutineScope get() = appEntryPoint.applicationScope()

    /**
     * SAF audio picker for the shortcut flow: the same OpenDocument contract
     * the ModelTab file picker uses, driven via createIntent/parseResult
     * because this plain Activity (deliberately not a ComponentActivity) has
     * no registerForActivityResult. The stateless contract is safe to share.
     */
    private val audioPickerContract = ActivityResultContracts.OpenDocument()

    // Local BroadcastReceiver to receive detected package from ChooserBroadcastReceiver
    private val chosenAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val detectedPackage = intent?.getStringExtra(ChooserBroadcastReceiver.EXTRA_DETECTED_PACKAGE)
            if (detectedPackage != null) {
                Log.i(TAG, "Detected source app via ChooserBroadcastReceiver: $detectedPackage")
                sourcePackage = detectedPackage
                // Cancel timeout since we got the result
                detectionTimeoutRunnable?.let { detectionTimeoutHandler?.removeCallbacks(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A recreation redelivers the share intent to a NEW instance. A
        // saved stamp from THIS process means the original instance's
        // copy-and-dispatch coroutine is still alive on the app scope:
        // re-running would copy and transcribe twice, so end the flow here.
        // A stamp from ANOTHER process means that process died mid-flow
        // (its coroutine died with it); re-running the redelivered intent
        // is the recovery, so the guard lets it through. First launch
        // (no stamp) and a recreation while the SAF picker merely waits
        // (nothing dispatched yet) also run the normal flow.
        val savedPid = savedInstanceState?.getInt(STATE_DISPATCH_PID, -1)
        if (savedPid == android.os.Process.myPid()) {
            Log.i(TAG, "Share flow already dispatched by this process; skipping re-dispatch")
            finish()
            return
        }

        Log.i(TAG, "Share received: action=${intent?.action}, type=${intent?.type}")

        // Register receiver to get chosen app from ChooserBroadcastReceiver
        try {
            registerReceiver(
                chosenAppReceiver,
                IntentFilter(ChooserBroadcastReceiver.ACTION_SHARE_CHOSEN),
                Context.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "Registered ChooserBroadcastReceiver listener")

            // Set timeout fallback (in case BroadcastReceiver doesn't fire)
            detectionTimeoutHandler = Handler(Looper.getMainLooper())
            detectionTimeoutRunnable = Runnable {
                Log.d(TAG, "Package detection timeout - using fallback")
                unregisterReceiver(chosenAppReceiver)
            }
            detectionTimeoutHandler?.postDelayed(detectionTimeoutRunnable!!, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ChooserBroadcastReceiver listener", e)
        }

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSendIntent(intent)
            else -> {
                Log.w(TAG, "Unexpected action: ${intent?.action}")
                cleanup()
                finish()
            }
        }
    }

    private fun handleSendIntent(intent: Intent) {
        // Try to detect the calling package (limited availability on modern Android)
        if (sourcePackage == null) {
            sourcePackage = callingActivity?.packageName
            if (sourcePackage != null) {
                Log.i(TAG, "Detected source app via callingActivity: $sourcePackage")
            }
        }

        // If still null, try getCallingPackage() for startActivityForResult scenarios
        if (sourcePackage == null) {
            @Suppress("DEPRECATION")
            sourcePackage = callingPackage
            if (sourcePackage != null) {
                Log.i(TAG, "Detected source app via callingPackage: $sourcePackage")
            }
        }

        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

        // If still null, resolve the content URI's authority to its OWNING PACKAGE via
        // PackageManager. The authority (e.g. "com.google.android.apps.nbu.files.provider")
        // is the FileProvider authority, NOT the package — resolveContentProvider() returns
        // the actual app package (e.g. "com.google.android.apps.nbu.files"), which then maps
        // to the human label ("Files") via AppInfoUtils.getAppName() at display time.
        if (sourcePackage == null && uri != null && uri.scheme == "content") {
            val authority = uri.authority
            if (authority != null) {
                val resolved = try {
                    packageManager.resolveContentProvider(authority, 0)?.packageName
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    null
                }
                if (resolved != null) {
                    sourcePackage = resolved
                    Log.i(TAG, "Detected source app from URI authority: $sourcePackage (authority: $authority)")
                }
            }
        }

        // Log detection result
        if (sourcePackage != null) {
            Log.i(TAG, "Source app detected: $sourcePackage")
        } else {
            Log.d(TAG, "Source app not detected - will use default preferences")
        }

        Log.i(TAG, "Handle share: URI=$uri, MIME=${intent.type}, source=$sourcePackage")

        if (uri == null) {
            // Dynamic launcher shortcuts (TASK-393) reach here by design: the
            // shortcut intent carries the alias component but cannot carry an
            // audio stream. With the shortcut marker, open the SAF audio
            // picker instead of dead-ending; the alias backend override is
            // re-derived from this intent's component after the pick, which
            // returns to this same Activity instance.
            if (intent.getBooleanExtra(EXTRA_FROM_SHORTCUT, false)) {
                launchAudioPicker()
                return
            }
            Log.e(TAG, "No EXTRA_STREAM in intent")
            showErrorToast(getString(R.string.no_audio_file))
            cleanup()
            finish()
            return
        }

        processSharedAudio(uri, intent.type)
    }

    /**
     * Opens the system SAF audio picker for the shortcut flow. The transparent
     * Activity stays alive (no finish) so the picker result can return to this
     * instance in [onActivityResult].
     */
    private fun launchAudioPicker() {
        startActivityForResult(
            audioPickerContract.createIntent(this, arrayOf("audio/*")),
            REQUEST_PICK_AUDIO,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_AUDIO) return
        val uri = audioPickerContract.parseResult(resultCode, data)
        if (uri == null) {
            // User backed out of the picker: nothing to transcribe; the same
            // quiet exit as the external-model chooser cancel path.
            cleanup()
            finish()
            return
        }
        processSharedAudio(uri, data?.type)
    }

    /**
     * The shared copy-and-dispatch flow for both audio sources: an EXTRA_STREAM
     * share and a shortcut-picker pick. Copies while the URI grant is held
     * (on IO: the copy is blocking work on potentially GB-scale videos and
     * must not sit on the MAIN thread), resolves the alias backend override
     * from the LAUNCH intent's component (the picker result returns to the
     * same instance, so the shortcut's alias is still this.intent's
     * component), then routes to the external chooser or the subtitle/ASR
     * dispatch. Runs on the process-lifetime @ApplicationScope (TASK-438: no
     * hand-built scopes; the flow must complete even after finish()).
     */
    private fun processSharedAudio(uri: Uri, mimeType: String?) {
        dispatchStarted = true
        appScope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) {
                SharedAudioHandler.copyToAppStorage(applicationContext, uri, mimeType)
            }

            val localPath: String = when (result) {
                is SharedAudioHandler.CopyResult.Success -> result.path
                // One message definition for every caller (the History browse FAB
                // shares it); the copy itself already logged the specific cause.
                else -> {
                    showErrorToast(result.userMessage(this@ShareReceiverActivity))
                    cleanup()
                    finish()
                    return@launch
                }
            }

            Log.i(TAG, "Copied to: $localPath")

            // Start service with file path and detected package
            val taskId = "share_${System.currentTimeMillis()}"

            // Resolve the backend override once (applies to both the ASR path and the subtitle
            // choice's "Transcribe audio" action). A share-target alias forces a specific backend.
            val backendOverride: String? = intent?.component?.className?.let { alias ->
                backendIdForAlias(alias, appEntryPoint.backendRegistry())?.also { backendId ->
                    Log.i(TAG, "Share target alias detected: $alias -> backend: $backendId")
                }
            }

            // External-family share target: the sentinel must become a concrete external:<id>
            // BEFORE any consumer (subtitle branch, timeout worker, service intent) sees it.
            if (backendOverride == EXTERNAL_FAMILY_BACKEND_ID) {
                showExternalModelChooser(taskId, localPath, appEntryPoint.externalModelStore())
                return@launch
            }

            dispatch(taskId, localPath, backendOverride)
        }
    }

    /**
     * Chooser for the ShareExternal family alias: a platform AlertDialog (this Activity is
     * deliberately not a ComponentActivity, so no Compose). Blocks until the user picks an
     * imported model, then continues the normal flow with the concrete external backend id.
     * Suspending: the record read is a DataStore access and must not park the
     * main thread (the TASK-517 rule); the caller already runs on Main.
     */
    private suspend fun showExternalModelChooser(taskId: String, localPath: String, store: com.antivocale.app.data.ExternalModelStore) {
        // The copy runs on the app scope and can outlive this Activity: a
        // recreation (or the recreation guard's finish()) between copy and
        // chooser leaves no window to attach a dialog to. The share is
        // dropped, but NOT silently: the same toast + error notification as
        // every other failure path (TASK-385's loss class).
        if (isFinishing || isDestroyed) {
            Log.w(TAG, "Activity gone before the external chooser could show; dropping share for $taskId")
            showErrorToast(getString(R.string.transcription_failed))
            return
        }
        val records = store.validRecords()

        if (records.isEmpty()) {
            // Unreachable in production (the alias component is disabled with no records),
            // guarded anyway so a stale component state degrades politely.
            com.antivocale.app.util.ToastCompat.show(this, R.string.external_none_imported)
            cleanup()
            finish()
            return
        }

        val labels = records.map { record ->
            record.displayName + if (record.languages.isEmpty()) "" else " (" + record.languages.joinToString(", ") + ")"
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.share_target_external)
            .setItems(labels) { _, which ->
                val chosen = records[which]
                Log.i(TAG, "External model chosen via share chooser: ${chosen.backendId}")
                appScope.launch(Dispatchers.Main) {
                    dispatch(taskId, localPath, chosen.backendId)
                }
            }
            .setOnCancelListener {
                cleanup()
                finish()
            }
            .show()
    }

    /** The subtitle probe branch plus the default ASR path, shared by every
     *  entry. Suspending: callers run it inside their own coroutine on Main
     *  (no nested launch hop). */
    private suspend fun dispatch(taskId: String, localPath: String, backendOverride: String?) {
        // TASK-517: the subtitle probe (MediaExtractor on potentially
        // GB-scale videos) and the preference reads inside offerIfTracks
        // are blocking IO; they ran on this Activity's MAIN thread, in ANR
        // territory for large files. The probe runs on Dispatchers.IO;
        // the toast/service/finish on the main thread after it resolves.
        val offered = withContext(Dispatchers.IO) {
            SubtitleChoice.offerIfTracks(
                this@ShareReceiverActivity, taskId, localPath,
                source = InferenceService.SOURCE_SHARE,
                sourcePackage = sourcePackage,
                backendOverride = backendOverride)
        }

        // GH #18: the decode probe gates only the ASR path; subtitle-only
        // shares were handled above (offered) and never reach here.
        val decodeError = withContext(Dispatchers.IO) {
            appEntryPoint.audioPreprocessor().probeDecodable(localPath)
        }
        if (decodeError != null) {
            showErrorToast(
                com.antivocale.app.audio.PreprocessingErrorMessages.localize(
                    this@ShareReceiverActivity, decodeError))
            // GH #18 review: the just-copied file is undecodable garbage;
            // delete it now, not at the 24h sweep.
            withContext(Dispatchers.IO) { File(localPath).delete() }
            cleanup()
            finish()
            return
        }

        if (offered) {
            // F5: the shared probe+offer (the same code the History browse
            // FAB runs); when a choice prompt is posted it owns the request
            // and the share flow ends here. The timed fallback worker
            // (user-configured timeout) falls back to ASR if the user
            // ignores the prompt; either tap cancels the worker.
            com.antivocale.app.util.ToastCompat.show(this, R.string.subtitles_found_title)
            cleanup()
            finish()
            return
        }

        // ---- Default ASR path ----
        val serviceIntent = buildServiceIntent(taskId, localPath, requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO, trackIndex = -1, backendOverride = backendOverride)

        // F6: unified enqueue (trampoline fallback on the API 31+
        // restriction). The Failed branch is the no-signal case (e.g.
        // FGS restricted AND notifications unavailable): say so instead of
        // toasting "transcription started" over a lost request.
        when (InferenceEnqueue.start(this, serviceIntent)) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> {
                Log.i(TAG, "Enqueued InferenceService for taskId: $taskId, source: $sourcePackage")
                val toastRes = if (InferenceService.isTranscribing.value)
                    R.string.added_to_queue
                else
                    R.string.transcription_started
                com.antivocale.app.util.ToastCompat.show(this, toastRes)
            }
            is InferenceEnqueue.Outcome.Failed -> {
                Log.e(TAG, "Could not enqueue transcription for taskId: $taskId")
                showErrorToast(getString(R.string.transcription_failed))
            }
        }

        cleanup()
        finish()
    }

    /**
     * Builds the [InferenceService] intent with the common extras shared by every path
     * (ASR, subtitle extraction, and the choice-notification tap actions).
     */
    private fun buildServiceIntent(
        taskId: String,
        localPath: String,
        requestType: String,
        trackIndex: Int,
        backendOverride: String?
    ): Intent = Intent(this, InferenceService::class.java).apply {
        putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, requestType)
        putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
        putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
        sourcePackage?.let { putExtra(EXTRA_SOURCE_PACKAGE, it) }
        // Don't pass a prompt - let InferenceService use the default from settings
        putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_SHARE)
        backendOverride?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        if (requestType == TaskerRequestReceiver.REQUEST_TYPE_SUBTITLES) {
            putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, trackIndex)
        }
    }


    private fun cleanup() {
        // Unregister receiver and cancel timeout
        try {
            detectionTimeoutRunnable?.let { detectionTimeoutHandler?.removeCallbacks(it) }
            unregisterReceiver(chosenAppReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Cleanup: receiver already unregistered or never registered")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The pid, not a boolean: a relaunch after process death must be
        // able to tell its stamp (dead coroutine, re-run to recover) from
        // an in-process recreation's stamp (live coroutine, skip).
        if (dispatchStarted) outState.putInt(STATE_DISPATCH_PID, android.os.Process.myPid())
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun showErrorToast(message: String) {
        // TASK-385 (WCAG 4.1.3): a ~4s Toast was the ONLY signal for share
        // failures (no notification, no Logs row: dispatch never happens), so a
        // user who missed it lost the failure entirely. Post a durable error
        // notification on the result channel TOO; the toast stays for immediate
        // feedback.
        com.antivocale.app.util.ToastCompat.show(this, message, Toast.LENGTH_LONG)
        // MUST: this path can run before anything else created the channel
        // (cold share, lines 213-250); notify() on an unregistered channel is
        // silently dropped on API 26+. create() is idempotent.
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(this)
        val notification = NotificationCompat.Builder(
            this, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(getString(R.string.transcription_failed))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, com.antivocale.app.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(errorNotificationId(message), notification)
    }
}
