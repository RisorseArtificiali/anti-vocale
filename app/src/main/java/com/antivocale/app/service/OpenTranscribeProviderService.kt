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
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.OpenTranscribeCapabilities
import com.antivocale.app.transcription.TranscriptionOrchestrator
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.CrashReporter
import com.antivocale.app.util.SharedAudioHandler
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
 * Run serialization is the orchestrator's own lock (one engine, one run at
 * a time, across every execution site), so a second client request waits
 * there like an in-app one; this service brackets only the foreground
 * lifetime. Session.cancel cancels the job and the client receives
 * ErrorType.CANCELLED; a dead client binder cancels the job too (the
 * contract's crashed-client rule), because every callback delivery is a
 * guarded binder call whose failure is the death signal.
 */
@AndroidEntryPoint
class OpenTranscribeProviderService : Service() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var orchestrator: TranscriptionOrchestrator
    @Inject lateinit var activeModelRepository: ActiveModelRepository
    @Inject lateinit var externalModelStore: ExternalModelStore

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)

    /** Jobs started but not finished; foreground drops when it reaches zero. */
    private val liveJobs = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        AppNotificationChannel.INFERENCE.create(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Defense in depth behind the component gate: a stale component
        // state (or a direct explicit-intent bind) must not reach the API.
        // The flow is cache-seeded, so this reads no disk.
        val enabled = runCatching {
            runBlocking { preferencesManager.openTranscribeEnabled.first() }
        }.getOrDefault(false)
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
            // Synchronous read on the binder thread; every flow involved is
            // cache-seeded, so this touches no disk. Any surprise degrades
            // instead of throwing across the binder.
            return runCatching {
                runBlocking { readCapabilities() }
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
            // Consent re-check on the same cache-seeded read onBind uses:
            // disabling the component does not unbind a client that bound
            // while the gate was open, so consent must hold per request,
            // not per binding.
            val enabled = runCatching {
                runBlocking { preferencesManager.openTranscribeEnabled.first() }
            }.getOrDefault(false)
            if (!enabled) {
                deliverError(callback, ErrorType.UNEXPECTED, "The provider is disabled")
                runCatching { audio.close() }
                return null
            }
            // Binder.getCallingUid is only valid during this transaction.
            val callingPackage = callingPackageName()
            val taskId = "opentranscribe-${UUID.randomUUID()}"
            liveJobs.incrementAndGet()
            val job = serviceScope.launch {
                runJob(this, audio, request, callback, callingPackage, taskId)
            }
            if (job.isCancelled) {
                // The service was destroyed between bind and this call, so
                // the launch produced an already-dead coroutine: the body
                // never runs, and the terminal plus the liveJobs bracket
                // are ours to deliver here.
                liveJobs.decrementAndGet()
                deliverError(callback, ErrorType.CANCELLED, null)
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
        // Every provider-initiated failure carries the same false/0 flags;
        // only the decode classification varies.
        fun fail(errorCode: String, message: String, decode: Boolean = false) {
            bridge.onError(taskId, errorCode, message,
                isShareRequest = false, isNoModelError = false, durationMs = 0,
                isMemoryFailure = false, isDecodeError = decode)
        }
        try {
            // Foreground promotion is best-effort: a client that bound from
            // the background can make startForeground throw (API 31+). The
            // bound job still runs, it only loses the foreground shield, so
            // the throw must not kill the request. Serialization is the
            // orchestrator's runMutex (one engine, one run at a time, every
            // execution site); liveJobs brackets only the foreground life.
            runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            // The TASK-432 pre-copy gate, the share path's rule: never fill
            // the target storage with a client's audio. A statSize of 0
            // means the size is unknown (a pipe): the gate stays open.
            val statSize = audio.statSize
            if (statSize > 0 && !SharedAudioHandler.hasFreeSpace(cacheDir.usableSpace, statSize)) {
                fail("OUT_OF_SPACE", "Not enough free storage to receive the audio")
                return
            }
            if (!spool(audio, spoolFile)) {
                // The descriptor could not be read (client died or handed
                // garbage): closest contract meaning.
                fail("SPOOL_FAILED", "Reading the audio failed", decode = true)
                return
            }
            // The API run gets a History row like a Tasker run (source and
            // calling package land on it); the orchestrator's run-terminal
            // cleanup no longer depends on the row existing.
            orchestrator.logQueued(
                taskId = taskId,
                requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
                filePath = spoolFile.absolutePath,
                sourcePackageName = callingPackage,
            )
            orchestrator.processRequest(
                taskId = taskId,
                requestType = TaskerRequestReceiver.REQUEST_TYPE_AUDIO,
                filePath = spoolFile.absolutePath,
                source = SOURCE_OPENTRANSCRIBE,
                sourcePackage = callingPackage,
                // Clients speak BCP-47 ("en-US"); the language policy's
                // vocabulary is a bare 639-1 code, so the primary subtag
                // is the pin and anything that normalizes to blank falls
                // back to the untouched auto default.
                languageOverride = request.languageHint
                    ?.substringBefore('-')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() },
                queuePosition = 1,
                queueTotal = 1,
                context = applicationContext,
                cacheDir = cacheDir,
                listener = bridge,
                coroutineScope = jobScope,
            )
        } catch (e: CancellationException) {
            // The cancel terminal must fire no matter WHERE cancellation
            // lands: waiting on the engine lock, spooling, or mid-run.
            bridge.deliverCancelled()
            // The spool file dies with this request, so an interrupted row
            // must not survive to offer a resume on a vanished input.
            runCatching { orchestrator.cancelIfPending(taskId, "Cancelled by the client", 0) }
            throw e
        } catch (e: Exception) {
            // Safety net for the exactly-one-terminal guarantee: any
            // unexpected throw between spool and the orchestrator's own
            // error delivery ends here instead of hanging the client until
            // its idle timeout; the bridge drops this when a terminal
            // already fired. The row is failed the same way, so no
            // eternally-processing opentranscribe row outlives the throw.
            fail("UNEXPECTED", e.message ?: e.javaClass.simpleName)
            runCatching { orchestrator.cancelIfPending(taskId, e.message ?: e.javaClass.simpleName, 0) }
        } finally {
            runCatching { spoolFile.delete() }
            // The binder handed us OUR dup of the descriptor; the client's
            // close cannot reach it, so the request settles with ours
            // closed (one fd per request, never leaked to finalization).
            runCatching { audio.close() }
            if (liveJobs.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /**
     * A direct terminal delivery outside the job lifecycle (the disabled
     * refusal, the dead-scope case): NOT used by the bridge's transport,
     * whose guarded delivery must see the binder failure to detect a dead
     * client; swallowing the exception here would hide exactly that.
     */
    private fun deliverError(callback: ITranscriptionCallback, type: Byte, message: String?) {
        runCatching {
            val error = TranscriptionError()
            error.type = type
            error.message = message
            callback.onTranscriptionError(error)
        }
    }

    /**
     * Copies the client's descriptor into our cache. The streams are not
     * closed here: the authoritative close is the PFD's, in runJob's
     * finally (the binder handed us OUR dup; the client's close cannot
     * reach it).
     */
    private fun spool(audio: ParcelFileDescriptor, target: File): Boolean {
        val input = FileInputStream(audio.fileDescriptor)
        val output = FileOutputStream(target)
        return try {
            input.copyTo(output, SPOOL_BUFFER_BYTES)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Spooling the client audio failed", e)
            false
        } finally {
            runCatching { output.close() }
        }
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
        // SharedAudioHandler owns the audio-format vocabulary (MimeMap plus
        // the manual table, parameter stripping, generic-"bin" rejection);
        // a private second table here would drift from the share path.
        val ext = SharedAudioHandler.resolveAudioExtension(request.mimeType, request.fileName)
        return ".${ext ?: "bin"}"
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

        override fun onError(type: Byte, message: String?) {
            guarded {
                val error = TranscriptionError()
                error.type = type
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

        /** The API's source label for rows and logs; never "share", so no share UX applies. */
        private const val SOURCE_OPENTRANSCRIBE = "opentranscribe"

        private const val SPOOL_BUFFER_BYTES = 64 * 1024
    }
}
