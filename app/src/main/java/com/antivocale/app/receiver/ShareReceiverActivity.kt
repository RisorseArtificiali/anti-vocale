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
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.receiver.ChooserBroadcastReceiver
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.SubtitleExtractor
import com.antivocale.app.transcription.SubtitleTrack
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.SharedAudioHandler
import com.antivocale.app.work.SubtitleChoiceTimeoutWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

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

        // The choice prompt auto-resolves to ASR after this delay if the user does nothing.
        // Keeps a shared video from silently hanging when the notification is ignored.
        internal const val SUBTITLE_CHOICE_TIMEOUT_MINUTES = 5L

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
            Log.e(TAG, "No EXTRA_STREAM in intent")
            showErrorToast(getString(R.string.no_audio_file))
            cleanup()
            finish()
            return
        }

        // Copy file while Activity has URI permission
        // Content URI permissions are tied to this Activity instance
        val result = SharedAudioHandler.copyToAppStorage(
            applicationContext,
            uri,
            intent.type
        )

        val localPath: String = when (result) {
            is SharedAudioHandler.CopyResult.Success -> result.path
            is SharedAudioHandler.CopyResult.UnsupportedFormat -> {
                Log.e(TAG, "Unsupported audio format: ${result.extension}")
                // result.extension comes from the sender's URI/MIME, so guard against
                // garbage before interpolating into the toast. A non-token extension
                // falls back to the generic "unknown format" message.
                if (result.extension.matches(Regex("^[a-zA-Z0-9]{1,8}$"))) {
                    showErrorToast(getString(R.string.unsupported_audio_format, result.extension))
                } else {
                    showErrorToast(getString(R.string.unknown_audio_format))
                }
                cleanup()
                finish()
                return
            }
            SharedAudioHandler.CopyResult.UnknownFormat -> {
                showErrorToast(getString(R.string.unknown_audio_format))
                cleanup()
                finish()
                return
            }
            SharedAudioHandler.CopyResult.Unreadable -> {
                showErrorToast(getString(R.string.failed_to_process_audio))
                cleanup()
                finish()
                return
            }
            is SharedAudioHandler.CopyResult.OutOfSpace -> {
                showErrorToast(getString(R.string.error_storage_full, result.neededMb))
                cleanup()
                finish()
                return
            }
        }

        Log.i(TAG, "Copied to: $localPath")

        // Start service with file path and detected package
        val taskId = "share_${System.currentTimeMillis()}"

        // Resolve the backend override once (applies to both the ASR path and the subtitle
        // choice's "Transcribe audio" action). A share-target alias forces a specific backend.
        // The entry point is resolved once here and handed to the external chooser, which
        // needs the same store from the same app-wide singleton.
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, BackendRegistryEntryPoint::class.java)
        val backendOverride: String? = intent?.component?.className?.let { alias ->
            backendIdForAlias(alias, entryPoint.backendRegistry())?.also { backendId ->
                Log.i(TAG, "Share target alias detected: $alias -> backend: $backendId")
            }
        }

        // External-family share target: the sentinel must become a concrete external:<id>
        // BEFORE any consumer (subtitle branch, timeout worker, service intent) sees it.
        if (backendOverride == EXTERNAL_FAMILY_BACKEND_ID) {
            showExternalModelChooser(taskId, localPath, entryPoint.externalModelStore())
            return
        }

        dispatch(taskId, localPath, backendOverride)
    }

    /**
     * Chooser for the ShareExternal family alias: a platform AlertDialog (this Activity is
     * deliberately not a ComponentActivity, so no Compose). Blocks until the user picks an
     * imported model, then continues the normal flow with the concrete external backend id.
     */
    private fun showExternalModelChooser(taskId: String, localPath: String, store: com.antivocale.app.data.ExternalModelStore) {
        val records = kotlinx.coroutines.runBlocking { store.validRecords() }

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
                dispatch(taskId, localPath, chosen.backendId)
            }
            .setOnCancelListener {
                cleanup()
                finish()
            }
            .show()
    }

    /** The subtitle probe branch plus the default ASR path, shared by every entry. */
    private fun dispatch(taskId: String, localPath: String, backendOverride: String?) {
        // ---- Subtitle probe branch ----
        // If the shared file is a video with readable text subtitle tracks, surface a choice
        // notification instead of starting ASR. The 5-min timeout worker falls back to ASR
        // if the user ignores the prompt; either tap cancels the worker.
        if (SharedAudioHandler.isVideoFile(localPath)) {
            val tracks = try {
                SubtitleExtractor.probe(localPath)
            } catch (e: Exception) {
                Log.w(TAG, "Subtitle probe failed for $localPath — proceeding to ASR", e)
                emptyList()
            }
            if (tracks.isNotEmpty()) {
                val track = pickBestTrack(tracks)
                postSubtitleChoiceNotification(taskId, localPath, track, backendOverride)
                enqueueChoiceTimeoutWorker(taskId, localPath, backendOverride)

                com.antivocale.app.util.ToastCompat.show(this, R.string.subtitles_found_title)
                Log.i(TAG, "Subtitles found (${tracks.size} tracks) — posted choice notification for taskId: $taskId")
                cleanup()
                finish()
                return
            }
            Log.i(TAG, "Video shared but no text subtitle tracks — starting ASR")
        }

        // ---- Default ASR path ----
        val serviceIntent = buildServiceIntent(taskId, localPath, requestType = "audio", trackIndex = -1, backendOverride = backendOverride)

        startForegroundService(serviceIntent)
        Log.i(TAG, "Started InferenceService for taskId: $taskId, source: $sourcePackage")

        val toastRes = if (InferenceService.isTranscribing.value)
            R.string.added_to_queue
        else
            R.string.transcription_started
        com.antivocale.app.util.ToastCompat.show(this, toastRes)

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
        if (requestType == "subtitles") {
            putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, trackIndex)
        }
    }

    /**
     * Picks the best subtitle track: the one whose language matches the user's transcription
     * language preference, else the first track. Languages are matched on the leading
     * ISO code (e.g. "it" in "it-IT" / "ita").
     */
    private fun pickBestTrack(tracks: List<SubtitleTrack>): SubtitleTrack {
        val preferred = try {
            val preferencesManager = EntryPointAccessors.fromApplication(
                applicationContext, SubtitlePrefsEntryPoint::class.java
            ).preferencesManager
            runBlocking { preferencesManager.transcriptionLanguage.first() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read transcription language pref, using first track", e)
            return tracks.first()
        }
        if (preferred.isBlank() ||
            preferred == TranscriptionLanguagePolicy.PREF_AUTO ||
            preferred == TranscriptionLanguagePolicy.PREF_SYSTEM
        ) {
            return tracks.first()
        }
        return tracks.firstOrNull { track ->
            track.language != null && (
                track.language.equals(preferred, ignoreCase = true) ||
                track.language.startsWith(preferred, ignoreCase = true) ||
                preferred.startsWith(track.language, ignoreCase = true)
            )
        } ?: tracks.first()
    }

    /**
     * Posts the high-priority choice notification with two actions: "Use subtitles" and
     * "Transcribe audio". Each action broadcasts to [NotificationActionReceiver], which
     * cancels the timeout worker and starts [InferenceService] with the right request type.
     */
    private fun postSubtitleChoiceNotification(
        taskId: String,
        localPath: String,
        track: SubtitleTrack,
        backendOverride: String?
    ) {
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(this)

        val languageLabel = track.language
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.subtitles_language_unknown)

        val baseExtras = Intent().apply {
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, track.trackIndex)
            sourcePackage?.let { putExtra(EXTRA_SOURCE_PACKAGE, it) }
            putExtra(InferenceService.EXTRA_SOURCE, InferenceService.SOURCE_SHARE)
            backendOverride?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        }

        fun choiceAction(action: String): PendingIntent {
            val actionIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtras(baseExtras)
            }
            return PendingIntent.getBroadcast(
                this,
                // Unique request codes per (action, taskId) so both actions coexist.
                (action + taskId).hashCode(),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(this, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(getString(R.string.subtitles_found_title))
            .setContentText(getString(R.string.subtitles_found_text, languageLabel))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_edit,
                getString(R.string.action_use_subtitles),
                choiceAction(NotificationActionReceiver.ACTION_USE_SUBTITLES)
            )
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.action_transcribe_audio),
                choiceAction(NotificationActionReceiver.ACTION_TRANSCRIBE_AUDIO)
            )
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(choiceNotificationId(taskId), notification)
        Log.i(TAG, "Posted subtitle choice notification (taskId=$taskId, language=${track.language})")
    }

    /**
     * Enqueues the expedited timeout worker that falls back to ASR if the user does not
     * tap either choice within [SUBTITLE_CHOICE_TIMEOUT_MINUTES]. UNIQUE per taskId so a
     * re-share replaces the previous pending timeout; cancelled by either notification tap.
     */
    private fun enqueueChoiceTimeoutWorker(
        taskId: String,
        localPath: String,
        backendOverride: String?
    ) {
        val request = OneTimeWorkRequestBuilder<SubtitleChoiceTimeoutWorker>()
            .setInitialDelay(SUBTITLE_CHOICE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    SubtitleChoiceTimeoutWorker.KEY_FILE_PATH to localPath,
                    SubtitleChoiceTimeoutWorker.KEY_TASK_ID to taskId,
                    SubtitleChoiceTimeoutWorker.KEY_SOURCE_PACKAGE to sourcePackage,
                    SubtitleChoiceTimeoutWorker.KEY_BACKEND_OVERRIDE to backendOverride
                )
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "subtitle-choice-$taskId",
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.i(TAG, "Enqueued subtitle choice timeout worker (${SUBTITLE_CHOICE_TIMEOUT_MINUTES} min) for taskId: $taskId")
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
