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
import com.antivocale.app.transcription.LlmTranscriptionBackend
import com.antivocale.app.transcription.Qwen3AsrBackend
import com.antivocale.app.transcription.SherpaOnnxBackend
import com.antivocale.app.transcription.NemotronStreamingBackend
import com.antivocale.app.transcription.SubtitleExtractor
import com.antivocale.app.transcription.SubtitleTrack
import com.antivocale.app.transcription.WhisperBackend
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
        private const val ALIAS_PARAKEET = "com.antivocale.app.ShareParakeet"
        private const val ALIAS_WHISPER = "com.antivocale.app.ShareWhisper"
        private const val ALIAS_QWEN3 = "com.antivocale.app.ShareQwen3"
        private const val ALIAS_GEMMA = "com.antivocale.app.ShareGemma"
        private const val ALIAS_NEMOTRON = "com.antivocale.app.ShareNemotron"

        // The choice prompt auto-resolves to ASR after this delay if the user does nothing.
        // Keeps a shared video from silently hanging when the notification is ignored.
        internal const val SUBTITLE_CHOICE_TIMEOUT_MINUTES = 5L

        // Stable notification id per taskId so the choice prompt can be cancelled by the
        // tap receiver or replaced on a re-share of the same taskId.
        internal fun choiceNotificationId(taskId: String): Int = taskId.hashCode()


        internal fun backendIdForAlias(aliasClassName: String): String? = when (aliasClassName) {
            ALIAS_PARAKEET -> SherpaOnnxBackend.BACKEND_ID
            ALIAS_WHISPER -> WhisperBackend.BACKEND_ID
            ALIAS_QWEN3 -> Qwen3AsrBackend.BACKEND_ID
            ALIAS_GEMMA -> LlmTranscriptionBackend.BACKEND_ID
            ALIAS_NEMOTRON -> NemotronStreamingBackend.BACKEND_ID
            else -> null
        }
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
        val localPath = SharedAudioHandler.copyToAppStorage(
            applicationContext,
            uri,
            intent.type
        )

        if (localPath == null) {
            Log.e(TAG, "Failed to copy shared audio")
            showErrorToast(getString(R.string.failed_to_process_audio))
            cleanup()
            finish()
            return
        }

        Log.i(TAG, "Copied to: $localPath")

        // Start service with file path and detected package
        val taskId = "share_${System.currentTimeMillis()}"

        // Resolve the backend override once (applies to both the ASR path and the subtitle
        // choice's "Transcribe audio" action). A share-target alias forces a specific backend.
        val backendOverride: String? = intent?.component?.className?.let { alias ->
            backendIdForAlias(alias)?.also { backendId ->
                Log.i(TAG, "Share target alias detected: $alias -> backend: $backendId")
            }
        }

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
        if (preferred.isBlank() || preferred == "auto" || preferred == "system") {
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
        com.antivocale.app.util.ToastCompat.show(this, message, Toast.LENGTH_LONG)
    }
}
