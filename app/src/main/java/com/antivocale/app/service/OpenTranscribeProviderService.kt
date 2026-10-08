package com.antivocale.app.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antivocale.app.BuildConfig
import com.antivocale.app.R
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.transcription.OpenTranscribeCapabilities
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.ITranscriptionSession
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * TASK-785 (GH #144): Anti-Vocale as an Open Transcribe contract provider.
 * Contract clients (Forkgram and friends) discover the app by the intent
 * action, bind, and hand a recorded audio file; the text comes back on the
 * callback. The bound service promotes itself to foreground while a job
 * runs so long transcriptions keep the app's foreground-service execution
 * path (the InferenceService precedent), then drops foreground when the
 * queue drains. The client owns the result: no result notifications, no
 * clipboard, no auto-save from this surface (the orchestrator's own row
 * bookkeeping is shared with every caller, Tasker included).
 *
 * The service deliberately exposes the v1-shape surface (getCapabilities
 * and transcribe only): omitting openStream keeps the transaction codes
 * canonical, and the contract sanctions unknown-method errors for calls a
 * client gates on contractVersion anyway. capabilities.streaming=false is
 * the honest report; the live-stream path is tracked separately.
 *
 * Jobs serialize through one Mutex: a second request waits instead of
 * racing the engine (the InferenceService queue, collapsed to the API's
 * one-at-a-time reality). Session.cancel cancels the job and the client
 * receives ErrorType.CANCELLED; a dead client binder cancels the job too
 * (the contract's crashed-client rule), because every callback delivery
 * is a guarded binder call whose failure is the death signal.
 */
@AndroidEntryPoint
class OpenTranscribeProviderService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var activeModelRepository: ActiveModelRepository
    @Inject lateinit var externalModelStore: ExternalModelStore

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)

    /** One transcription at a time; a second request waits here. */
    private val jobMutex = Mutex()

    /** Jobs started but not finished; foreground drops when it reaches zero. */
    private val liveJobs = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannel.INFERENCE.create(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Defense in depth behind the component gate: a stale component
        // state (or a direct explicit-intent bind) must not reach the API.
        val enabled = runCatching {
            runBlocking {
                withTimeoutOrNull(PREF_READ_TIMEOUT_MS) {
                    preferencesManager.openTranscribeEnabled.first()
                }
            }
        }.getOrDefault(null) ?: false
        if (!enabled) return null
        return binder
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private val binder = object : ITranscriptionService.Stub() {

        override fun getCapabilities(): TranscriberCapabilities {
            // Bounded synchronous read on the binder thread: the flows are
            // cache-seeded, so this is normally instant; the timeout keeps
            // a wedged DataStore from hanging the client.
            return runCatching {
                runBlocking {
                    withTimeoutOrNull(PREF_READ_TIMEOUT_MS) { readCapabilities() }
                } ?: degradedCapabilities()
            }.getOrDefault(degradedCapabilities())
        }

        override fun transcribe(
            audio: ParcelFileDescriptor?,
            request: TranscriptionRequest?,
            callback: ITranscriptionCallback?,
        ): ITranscriptionSession? {
            if (callback == null || audio == null || request == null) {
                runCatching { audio?.close() }
                return null
            }
            // Binder.getCallingUid is only valid during this transaction.
            val callingPackage = callingPackageName()
            val taskId = "opentranscribe-${UUID.randomUUID()}"
            liveJobs.incrementAndGet()
            val job = serviceScope.launch {
                runJob(this, audio, request, callback, callingPackage, taskId)
            }
            return object : ITranscriptionSession.Stub() {
                override fun cancel() {
                    job.cancel()
                }
            }
        }
    }

    private suspend fun readCapabilities(): TranscriberCapabilities {
        val active = activeModelRepository.activeModelFlow.first()
        val record = active.backendId
            .takeIf { it.startsWith(ExternalModelRecord.BACKEND_ID_PREFIX) }
            ?.let { externalModelStore.byId(it.removePrefix(ExternalModelRecord.BACKEND_ID_PREFIX)) }
        return OpenTranscribeCapabilities.derive(
            versionName = BuildConfig.VERSION_NAME,
            modelPath = active.modelPath,
            externalRecord = record,
            catalogEntry = if (record == null) BundledCatalog.byId(active.backendId) else null,
        )
    }

    private fun degradedCapabilities(): TranscriberCapabilities = OpenTranscribeCapabilities.derive(
        versionName = BuildConfig.VERSION_NAME,
        modelPath = null,
        externalRecord = null,
        catalogEntry = null,
    )

    private suspend fun runJob(
        jobScope: CoroutineScope,
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest,
        callback: ITranscriptionCallback,
        callingPackage: String?,
        taskId: String,
    ) {
        val bridge = OpenTranscribeCallbackBridge(RemoteEmitter(callback) { jobScope.cancel() })
        val spoolFile = File(cacheDir, "$taskId${spoolExtension(request)}")
        try {
            try {
                // The cancel terminal must fire no matter WHERE cancellation
                // lands: waiting on the mutex, spooling, or inside the run.
                jobMutex.withLock {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    try {
                        if (!runCatching { spool(audio, spoolFile) }.getOrDefault(false)) {
                            // The descriptor could not be read (client died
                            // or handed garbage): closest contract meaning.
                            bridge.onError(taskId, "SPOOL_FAILED", "Reading the audio failed",
                                isShareRequest = false, isNoModelError = false, durationMs = 0,
                                isMemoryFailure = false, isDecodeError = true)
                            return@withLock
                        }
                        // TASK-526 lesson (the SubtitleChoiceTimeoutWorker
                        // precedent): processRequest's terminal writes are
                        // row-conditional, and a run without a QUEUED row
                        // leaves the partial-transcription seed uncleared
                        // (a false "was interrupted" offer on the next app
                        // open). Create the row ourselves, like every
                        // direct caller must.
                        orchestrator.logQueued(
                            taskId = taskId,
                            requestType = REQUEST_TYPE_AUDIO,
                            filePath = spoolFile.absolutePath,
                            sourcePackageName = callingPackage,
                        )
                        orchestrator.processRequest(
                            taskId = taskId,
                            requestType = REQUEST_TYPE_AUDIO,
                            filePath = spoolFile.absolutePath,
                            source = SOURCE_OPENTRANSCRIBE,
                            sourcePackage = callingPackage,
                            languageOverride = request.languageHint?.takeIf { it.isNotBlank() },
                            queuePosition = 1,
                            queueTotal = 1,
                            context = applicationContext,
                            cacheDir = cacheDir,
                            listener = bridge,
                            coroutineScope = jobScope,
                        )
                    } finally {
                        runCatching { spoolFile.delete() }
                    }
                }
            } catch (e: CancellationException) {
                bridge.deliverCancelled()
                throw e
            }
        } finally {
            if (liveJobs.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /**
     * Copies the client's descriptor into our cache. The stream is
     * deliberately NOT closed: the client owns the descriptor and closes
     * it once the request settles (closing here would race its close).
     */
    private fun spool(audio: ParcelFileDescriptor, target: File): Boolean {
        val input = FileInputStream(audio.fileDescriptor)
        val output = FileOutputStream(target)
        var ok = true
        try {
            input.copyTo(output, SPOOL_BUFFER_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "Spooling the client audio failed", e)
            ok = false
        } finally {
            runCatching { output.close() }
        }
        return ok
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AppNotificationChannel.INFERENCE.id)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.opentranscribe_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun callingPackageName(): String? = runCatching {
        packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull()
    }.getOrNull()

    private fun spoolExtension(request: TranscriptionRequest): String {
        val fromName = request.fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in KNOWN_AUDIO_EXTENSIONS }
        if (fromName != null) return ".$fromName"
        val fromMime = when (request.mimeType?.lowercase()) {
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus", "audio/ogg;codecs=opus" -> "opus"
            "audio/mp4", "video/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            else -> null
        }
        return fromMime?.let { ".$it" } ?: DEFAULT_EXTENSION
    }

    /**
     * The transport half of the bridge: every delivery is a guarded binder
     * call; a failure means the client died, and the contract's rule is to
     * stop the work, so the job is cancelled.
     */
    private class RemoteEmitter(
        private val callback: ITranscriptionCallback,
        private val onDeadClient: () -> Unit,
    ) : OpenTranscribeEmitter {

        override fun onProgress(text: String) {
            guarded { callback.onTranscriptionProgress(text) }
        }

        override fun onSegment(startMs: Long, endMs: Long, text: String) {
            guarded { callback.onTranscriptionSegment(startMs, endMs, text) }
        }

        override fun onResult(text: String) {
            guarded { callback.onTranscriptionResult(text) }
        }

        override fun onError(type: Byte, language: String?, message: String?) {
            guarded {
                val error = TranscriptionError()
                error.type = type
                error.language = language
                error.message = message
                callback.onTranscriptionError(error)
            }
        }

        private fun guarded(delivery: () -> Unit) {
            if (runCatching(delivery).isFailure) onDeadClient()
        }
    }

    companion object {
        private const val TAG = "OpenTranscribeProvider"

        /**
         * Reserved-range contract (see ResultNotificationFactory's base
         * table): the provider's foreground notification, next free fixed
         * id under 3000 after the 1009 model-switch confirmation.
         */
        const val NOTIFICATION_ID = 1011

        /** Mirrors the Tasker audio vocabulary (TaskerRequestReceiver.REQUEST_TYPE_AUDIO). */
        private const val REQUEST_TYPE_AUDIO = "audio"

        /** The API's source label for rows and logs; never "share", so no share UX applies. */
        const val SOURCE_OPENTRANSCRIBE = "opentranscribe"

        private const val PREF_READ_TIMEOUT_MS = 2_000L
        private const val SPOOL_BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_EXTENSION = ".audio.bin"
        private val KNOWN_AUDIO_EXTENSIONS = setOf("ogg", "opus", "m4a", "mp3", "wav", "flac")
    }
}
