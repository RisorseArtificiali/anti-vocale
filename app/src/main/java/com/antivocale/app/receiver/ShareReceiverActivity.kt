package com.antivocale.app.receiver

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import com.antivocale.app.R
import com.antivocale.app.MainActivity
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.VoiceNoteDuplicateGuard
import com.antivocale.app.receiver.ChooserBroadcastReceiver
import com.antivocale.app.service.AudioRequestIntent
import com.antivocale.app.service.InferenceEnqueue
import com.antivocale.app.service.InferenceService
import com.antivocale.app.service.ResultNotificationFactory
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.ui.dialogs.DuplicateFoundDialog
import com.antivocale.app.ui.dialogs.ExternalModelChooserDialog
import com.antivocale.app.ui.theme.AntiVocaleTheme
import com.antivocale.app.ui.theme.fromName
import com.antivocale.app.ui.theme.TextScale
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.SharedAudioHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID

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
 * Hilt entry point for fetching [PreferencesManager] from the NON-activity
 * share surfaces (SubtitleChoice and the receivers): the annotation-free
 * receiver classes cannot field-inject. The activity itself injects
 * [ShareReceiverActivity.preferencesManager] directly.
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
/**
 * The app-wide singletons the share flow needs, injected now that the class
 * is a ComponentActivity (the EntryPoint seam's no-activity precondition
 * vanished with the Compose surface; [SubtitlePrefsEntryPoint] stays for the
 * non-activity receivers).
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @javax.inject.Inject lateinit var backendRegistry: BackendRegistry
    @javax.inject.Inject lateinit var externalModelStore: com.antivocale.app.data.ExternalModelStore
    @javax.inject.Inject lateinit var voiceNoteIdentityCache: VoiceNoteIdentityCache
    @javax.inject.Inject lateinit var audioPreprocessor: com.antivocale.app.audio.AudioPreprocessor
    @javax.inject.Inject lateinit var transcriptionOrchestrator: com.antivocale.app.transcription.TranscriptionOrchestrator
    @javax.inject.Inject lateinit var duplicateGuard: com.antivocale.app.data.VoiceNoteDuplicateGuard
    @javax.inject.Inject lateinit var preferencesManager: PreferencesManager

    @com.antivocale.app.di.ApplicationScope
    @javax.inject.Inject lateinit var injectedAppScope: CoroutineScope


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
        // the injected app singleton and pass it in.
        // The ShareExternal family alias (single source: ShareTargetManager) is resolved
        // to a SENTINEL here; the instance flow replaces it with a concrete external:<id>.
        internal const val EXTERNAL_FAMILY_BACKEND_ID = "external"

        internal fun backendIdForAlias(aliasClassName: String, registry: BackendRegistry): String? =
            if (aliasClassName == com.antivocale.app.data.ShareTargetManager.EXTERNAL_FAMILY_ALIAS) EXTERNAL_FAMILY_BACKEND_ID
            else registry.byShareAlias(aliasClassName)?.backendId

        /**
         * TASK-927: the shared audio URIs of a share intent, in delivery
         * order. ACTION_SEND reads the single EXTRA_STREAM;
         * ACTION_SEND_MULTIPLE reads the ParcelableArrayList (null and
         * non-Uri entries dropped: the deprecated generic read does no
         * element validation, and this component is exported and advertised
         * for ACTION_SEND_MULTIPLE, so a foreign Parcelable must not reach
         * the flow). Empty means the intent carries no audio at all.
         */
        internal fun extractSharedUris(intent: Intent): List<Uri> =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.filterIsInstance<Uri>()
                    .orEmpty()
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
    }

    private var sourcePackage: String? = null
    private var detectionTimeoutHandler: Handler? = null
    private var detectionTimeoutRunnable: Runnable? = null

    /** Set when the copy-and-dispatch coroutine starts; the only state the
     *  recreation guard needs (see [onSaveInstanceState]). */
    private var dispatchStarted = false

    /**
     * TASK-927: files still owed their flow completion in the current
     * ACTION_SEND_MULTIPLE batch; 0 for every single-file entry. [endFlow]
     * counts one resolution per file and finishes the transparent Activity
     * only on the last: an early destroy would revoke the read grants of
     * the URIs not yet copied, and the per-file dialogs need a live window
     * to attach to.
     */
    private var batchFilesRemaining = 0

    /** TASK-927 review V5: bounds the batch to one concurrent app-storage
     *  copy, so the per-copy free-space gate cannot be passed by N flows
     *  that together exhaust storage mid-stream. */
    private val batchCopyGate = kotlinx.coroutines.sync.Semaphore(permits = 1)

    /**
     * The pending user interactions the Compose surface (see [onCreate]'s
     * setContent) renders. The share flows append; the user's answer removes
     * its own entry and runs the arm the old platform dialog carried.
     * A batch can pend several at once (one per file, e.g. two duplicates or
     * two family-alias choosers), stacked in append order exactly like the
     * platform dialogs they replaced. Internal so the state-holder test can
     * seed and drive the arms without running the copy flow.
     */
    internal val pendingDialogs = mutableStateListOf<PendingDialog>()

    /**
     * Installs the Compose dialog surface on first use. Platform-theme
     * translucent window is fine: Material3 composes its own theme, no
     * AppCompat host needed. The SAME brand/mode/text-scale the main
     * activity passes (review CR1: a look-and-feel fix that ignored the
     * user's saved theme would diverge from the History surface sharing
     * this dialog); read once per flow, reactive enough for a trampoline.
     * runBlocking first() reads only the warm preference cache (the
     * TaskerRequestReceiver gate's documented precedent).
     */
    private var dialogSurfaceInstalled = false
    private fun ensureDialogSurface() {
        if (dialogSurfaceInstalled) return
        dialogSurfaceInstalled = true
        val theme = runBlocking {
            runCatching { ThemeType.valueOf(preferencesManager.themePreference.first()) }
                .getOrDefault(ThemeType.DEFAULT)
        }
        val themeMode = runBlocking {
            runCatching { ThemeMode.valueOf(preferencesManager.themeMode.first()) }
                .getOrDefault(ThemeMode.SYSTEM)
        }
        val textScale = TextScale.fromName(
            runBlocking { preferencesManager.textScalePreference.first() })
        setContent {
            AntiVocaleTheme(brand = theme, mode = themeMode, textScale = textScale) {
                pendingDialogs.forEach { entry ->
                    // key() pins positional memoization to the entry: removing
                    // a mid-list dialog must not shift a survivor onto the
                    // removed one's slots (review CR2).
                    androidx.compose.runtime.key(entry.taskId) {
                        when (entry) {
                            is PendingDialog.Duplicate -> DuplicateFoundDialog(
                                modelName = entry.modelName,
                                onOpen = { onDuplicateOpen(entry) },
                                onReprocess = { onDuplicateReprocess(entry) },
                            )

                            is PendingDialog.ExternalChooser -> ExternalModelChooserDialog(
                                records = entry.records,
                                onPick = { chosen -> onExternalChooserPick(entry, chosen) },
                                onDismiss = { onExternalChooserCancel(entry) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Accepted hole (round-2 review CR6): a recreation the manifest
    // configChanges list does NOT cover (locale change, some fold/posture
    // cases) kills a pended dialog with its window and the dispatch-pid
    // guard finishes the new instance, so that file's share is lost without
    // a toast; the covered set (V6) keeps the common rotations alive.

    /** One pending interaction. Every variant carries its flow's unique
     *  per-file taskId, so two entries are never data-equal twins; the
     *  interface owns it because the surface keys composition on it. */
    internal sealed interface PendingDialog {
        val taskId: String

        /** TASK-842 duplicate prompt: open the existing row or reprocess. */
        data class Duplicate(
            val modelName: String,
            val existingTaskId: String,
            override val taskId: String,
            val localPath: String,
            val backendOverride: String?,
            val contentHash: String,
        ) : PendingDialog

        /** ShareExternal family chooser: pick one imported model. */
        data class ExternalChooser(
            override val taskId: String,
            val localPath: String,
            val records: List<ExternalModelRecord>,
            val contentHash: String?,
        ) : PendingDialog
    }

    /** The process-lifetime scope (TASK-438 rule: no hand-built scopes). The
     *  share flow must run to completion even after finish(). */
    private val appScope: CoroutineScope get() = injectedAppScope

    /**
     * SAF audio picker for the shortcut flow (the same OpenDocument contract
     * the ModelTab file picker uses). The Activity Result API now that the
     * class is a ComponentActivity: the register must be an unconditioned
     * field init (STARTED at latest), so it exists before the flow that
     * launches it.
     */
    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            // User backed out of the picker: nothing to transcribe; the same
            // quiet exit as the external-model chooser cancel path.
            endFlow()
        } else {
            processSharedAudio(uri, null)
        }
    }

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

        // The dialog surface is installed lazily by [ensureDialogSurface]
        // on the FIRST pended dialog (round-2 efficiency review): the common
        // copy-and-finish share pays no composition, no theme reads.

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
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultipleIntent(intent)
            else -> {
                Log.w(TAG, "Unexpected action: ${intent?.action}")
                endFlow()
            }
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val uris = extractSharedUris(intent)
        val uri = uris.firstOrNull()
        resolveSourcePackage(uri)
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
            endFlow()
            return
        }

        processSharedAudio(uri, intent.type)
    }

    /**
     * TASK-927: the ACTION_SEND_MULTIPLE entry. Each EXTRA_STREAM URI rides
     * the SAME copy-and-dispatch flow a single share uses; the batch counter
     * set here keeps the Activity alive until every flow resolves.
     */
    private fun handleSendMultipleIntent(intent: Intent) {
        val uris = extractSharedUris(intent)
        resolveSourcePackage(uris.firstOrNull())
        Log.i(TAG, "Handle batch share: ${uris.size} URI(s), MIME=${intent.type}, source=$sourcePackage")

        if (uris.isEmpty()) {
            Log.e(TAG, "No EXTRA_STREAM in intent")
            showErrorToast(getString(R.string.no_audio_file))
            endFlow()
            return
        }

        batchFilesRemaining = uris.size
        uris.forEach { processSharedAudio(it, intent.type) }
    }

    /**
     * The caller-attribution ladder shared by every share entry: the calling
     * component, then the calling package, then the content URI's authority
     * resolved to its OWNING PACKAGE via PackageManager. The authority (e.g.
     * "com.google.android.apps.nbu.files.provider") is the FileProvider
     * authority, NOT the package itself; resolveContentProvider() returns
     * the actual app package (e.g. "com.google.android.apps.nbu.files"), which then maps
     * to the human label ("Files") via AppInfoUtils.getAppName() at display time.
     * A batch resolves once against the first URI: every
     * EXTRA_STREAM URI in one intent comes from the same sender.
     */
    private fun resolveSourcePackage(firstUri: Uri?) {
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

        if (sourcePackage == null && firstUri != null && firstUri.scheme == "content") {
            val authority = firstUri.authority
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
    }

    /**
     * Opens the system SAF audio picker for the shortcut flow. The transparent
     * Activity stays alive (no finish) so the picker result can return to this
     * instance through [audioPicker].
     */
    private fun launchAudioPicker() {
        audioPicker.launch(arrayOf("audio/*"))
    }

    /**
     * TASK-927 review V3: one per-file share-flow run on the app scope.
     * Every exit must account its [endFlow] slot: a throw used to die into
     * the scope's CrashReporter handler and stall the batch counter forever
     * (and in the single case leave the transparent window up), so the
     * catch resolves the slot like the normal paths do.
     */
    private fun launchShareFlow(block: suspend () -> Unit) {
        appScope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Share flow failed", t)
                if (!isFinishing && !isDestroyed) {
                    showErrorToast(getString(R.string.transcription_failed))
                }
                endFlow()
            }
        }
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
        launchShareFlow {
            // TASK-578: a content-URI grant dies with the activity; the
            // copy must complete inside this activity's lifetime. It runs
            // here (Main dispatcher, called from onCreate, no finish() path
            // can fire while withContext(IO) holds the main thread
            // suspended), so the grant is alive for the whole copy. Voice
            // messages are small (1-10MB): the blocking IO is bounded.
            // TASK-927 review V5: the free-space gate passes per copy, so N
            // concurrent batch copies could collectively exhaust storage
            // mid-stream; one copy at a time (uncontended in the single case).
            val result = batchCopyGate.withPermit {
                withContext(Dispatchers.IO) {
                    SharedAudioHandler.copyToAppStorage(applicationContext, uri, mimeType)
                }
            }

            val localPath: String = when (result) {
                is SharedAudioHandler.CopyResult.Success -> result.path
                // One message definition for every caller (the History browse FAB
                // shares it); the copy itself already logged the specific cause.
                else -> {
                    showErrorToast(result.userMessage(this@ShareReceiverActivity))
                    endFlow()
                    return@launchShareFlow
                }
            }

            Log.i(TAG, "Copied to: $localPath")

            // Start service with file path and detected package. The uuid
            // fragment mirrors the copy's file name: a batch of
            // sub-millisecond copies could otherwise stamp two rows with
            // the same millisecond taskId (getByTaskId is first-match).
            val taskId = "share_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

            // TASK-677 (GH #92 import half): a handed subtitle FILE is an
            // import, not audio. Its cues become the transcript (ASR
            // skipped), so it routes before the external-alias, video and
            // decode-probe branches: a text file has no alias to resolve
            // and would only fail the decode probe.
            if (SharedAudioHandler.isSubtitleFile(localPath)) {
                dispatchSubtitleImport(taskId, localPath)
                return@launchShareFlow
            }

            // Resolve the backend override once (applies to both the ASR path and the subtitle
            // choice's "Transcribe audio" action). A share-target alias forces a specific backend.
            val backendOverride: String? = intent?.component?.className?.let { alias ->
                backendIdForAlias(alias, backendRegistry)?.also { backendId ->
                    Log.i(TAG, "Share target alias detected: $alias -> backend: $backendId")
                }
            }

            // TASK-842: the intake content hash, once, on IO (the
            // interactive duplicate guard's key; rides the service intent
            // so the row carries it for FUTURE intakes).
            val contentHash = duplicateGuard.hashOnIo(localPath)

            // External-family share target: the sentinel must become a concrete external:<id>
            // BEFORE any consumer (subtitle branch, timeout worker, service intent) sees it.
            if (backendOverride == EXTERNAL_FAMILY_BACKEND_ID) {
                showExternalModelChooser(
                    taskId, localPath, externalModelStore, contentHash)
                return@launchShareFlow
            }

            if (contentHash != null &&
                offerDuplicateDialogIfNeeded(taskId, localPath, backendOverride, contentHash)
            ) {
                return@launchShareFlow  // the dialog owns the request from here
            }

            dispatch(taskId, localPath, backendOverride, contentHash)
        }
    }

    /**
     * Chooser for the ShareExternal family alias: enqueues the pending
     * chooser the Compose surface renders, then returns; the pick arm
     * ([onExternalChooserPick]) continues the flow with the concrete
     * external backend id. Suspending: the record read is a DataStore
     * access and must not park the main thread (the TASK-517 rule); the
     * caller already runs on Main.
     */
    private suspend fun showExternalModelChooser(
        taskId: String,
        localPath: String,
        store: com.antivocale.app.data.ExternalModelStore,
        contentHash: String? = null,
    ) {
        // The copy runs on the app scope and can outlive this Activity: a
        // recreation (or the recreation guard's finish()) between copy and
        // chooser leaves no window to attach a dialog to. The share is
        // dropped, but NOT silently: the same toast + error notification as
        // every other failure path (TASK-385's loss class).
        if (isFinishing || isDestroyed) {
            Log.w(TAG, "Activity gone before the external chooser could show; dropping share for $taskId")
            showErrorToast(getString(R.string.transcription_failed))
            // TASK-927 review V3: this exit bypasses every normal endFlow
            // arm; account the slot (or finish, single case) here.
            endFlow()
            return
        }
        val records = store.validRecords()

        if (records.isEmpty()) {
            // Unreachable in production (the alias component is disabled with no records),
            // guarded anyway so a stale component state degrades politely.
            com.antivocale.app.util.ToastCompat.show(this, R.string.external_none_imported)
            endFlow()
            return
        }

        ensureDialogSurface()
        pendingDialogs.add(PendingDialog.ExternalChooser(taskId, localPath, records, contentHash))
    }

    /** The subtitle probe branch plus the default ASR path, shared by every
     *  entry. Suspending: callers run it inside their own coroutine on Main
     *  (no nested launch hop). */
    private suspend fun dispatch(
        taskId: String,
        localPath: String,
        backendOverride: String?,
        contentHash: String? = null,
    ) {
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
                backendOverride = backendOverride,
                contentHash = contentHash)
        }

        // GH #18: the decode probe gates only the ASR path; subtitle-only
        // shares were handled above (offered) and never reach here.
        val decodeError = withContext(Dispatchers.IO) {
            audioPreprocessor.probeDecodable(localPath)
        }
        if (decodeError != null) {
            showErrorToast(
                com.antivocale.app.audio.PreprocessingErrorMessages.localize(
                    this@ShareReceiverActivity, decodeError))
            // GH #18 review: the just-copied file is undecodable garbage;
            // delete it now, not at the 24h sweep.
            withContext(Dispatchers.IO) { File(localPath).delete() }
            endFlow()
            return
        }

        if (offered) {
            // F5: the shared probe+offer (the same code the History browse
            // FAB runs); when a choice prompt is posted it owns the request
            // and the share flow ends here. The timed fallback worker
            // (user-configured timeout) falls back to ASR if the user
            // ignores the prompt; either tap cancels the worker.
            com.antivocale.app.util.ToastCompat.show(this, R.string.subtitles_found_title)
            endFlow()
            return
        }

        // ---- Default ASR path ----
        // TASK-736: match the shared audio against the identity cache (the
        // free candidates check first; the duration probe runs only when a
        // voice note from this package is actually cached). TASK-271 moved
        // the probe + matcher into VoiceNoteSenderResolution so the share
        // flow and the automatic route share one implementation.
        val senderName = withContext(Dispatchers.IO) {
            VoiceNoteSenderResolution.match(
                voiceNoteIdentityCache,
                audioPreprocessor,
                sourcePackage,
                localPath)
        }
        val serviceIntent = AudioRequestIntent.make(
            this, InferenceService.SOURCE_SHARE, taskId, localPath,
            sourcePackage = sourcePackage,
            backendOverride = backendOverride,
            senderName = senderName,
            contentHash = contentHash)

        // F6: unified enqueue (trampoline fallback on the API 31+
        // restriction). The Failed branch is the no-signal case (e.g.
        // FGS restricted AND notifications unavailable): say so instead of
        // toasting "transcription started" over a lost request.
        enqueueAndFinish(taskId, serviceIntent, "InferenceService")
    }

    /**
     * TASK-842 (GH #157 part 3): the interactive duplicate guard. Looks up
     * the fresh copy's content hash against completed rows FOR THE MODEL
     * THIS RUN WOULD USE (the same display-name derivation the row will
     * stamp); on a hit, enqueues the pending duplicate prompt the Compose
     * surface renders (the same [DuplicateFoundDialog] the History browse
     * FAB shows) and it OWNS the request. Returns true when the prompt took
     * over (the caller must not dispatch); false when there is no duplicate
     * (the caller proceeds) or the Activity is already gone (reprocess is
     * the safe default: never lose a share over a dialog). A destroy
     * between the entry check and the prompt re-check falls back to
     * reprocess the same way.
     */
    private suspend fun offerDuplicateDialogIfNeeded(
        taskId: String,
        localPath: String,
        backendOverride: String?,
        contentHash: String,
    ): Boolean {
        if (isFinishing || isDestroyed) return false
        val hit = duplicateGuard
            .lookup(this, transcriptionOrchestrator, contentHash, backendOverride)
            ?: return false
        val existing = hit.row
        // Three suspension points sit between the entry check and the
        // append: re-check here so a destroy in that window cannot leave a
        // prompt pointing at a dead window.
        if (isFinishing || isDestroyed) return false
        Log.i(TAG, "Duplicate content for model=${hit.modelName}: opening-or-reprompting taskId=${existing.taskId}")

        ensureDialogSurface()
        pendingDialogs.add(
            PendingDialog.Duplicate(
                modelName = hit.modelName,
                existingTaskId = existing.taskId,
                taskId = taskId,
                localPath = localPath,
                backendOverride = backendOverride,
                contentHash = contentHash,
            )
        )
        return true
    }

    /**
     * The arms the old platform dialog carried, now driven by the Compose
     * surface. The endFlow accounting is untouched: every arm runs the
     * exact resolution path its dialog callback ran. Entries are removed
     * by identity, since a batch can pend data-shaped equals.
     */
    private fun removePendingDialog(entry: PendingDialog) {
        pendingDialogs.removeAll { it === entry }
    }

    /** The open arm: land on the existing transcript row. Also the answer
     *  to a dismiss outside the buttons (the conservative arm). */
    internal fun onDuplicateOpen(entry: PendingDialog.Duplicate) {
        removePendingDialog(entry)
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, entry.existingTaskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        endFlow()
    }

    /** The reprocess arm: run the flow's dispatch with this request's backend. */
    private fun onDuplicateReprocess(entry: PendingDialog.Duplicate) {
        removePendingDialog(entry)
        launchShareFlow {
            dispatch(entry.taskId, entry.localPath, entry.backendOverride, entry.contentHash)
        }
    }

    /** The chooser's row-tap arm: continue the flow with the chosen backend. */
    private fun onExternalChooserPick(entry: PendingDialog.ExternalChooser, chosen: ExternalModelRecord) {
        removePendingDialog(entry)
        Log.i(TAG, "External model chosen via share chooser: ${chosen.backendId}")
        launchShareFlow {
            if (entry.contentHash != null &&
                offerDuplicateDialogIfNeeded(entry.taskId, entry.localPath, chosen.backendId, entry.contentHash)
            ) {
                return@launchShareFlow  // the dialog owns the request from here
            }
            dispatch(entry.taskId, entry.localPath, chosen.backendId, entry.contentHash)
        }
    }

    /** The chooser's dismiss arm (the platform dialog's cancel listener). */
    internal fun onExternalChooserCancel(entry: PendingDialog.ExternalChooser) {
        removePendingDialog(entry)
        endFlow()
    }

    /**
     * TASK-677 (GH #92 import half): the .srt/.vtt import dispatch. The file
     * is the source itself: no backend override (no model runs), no subtitle
     * choice prompt (there is no second arm to choose), and no decode probe
     * (there is no audio). The service parses it and reports through the
     * standard success funnel with the cues preserved.
     */
    private fun dispatchSubtitleImport(taskId: String, localPath: String) {
        val serviceIntent = AudioRequestIntent.subtitleImport(
            this, taskId, localPath, sourcePackage = sourcePackage)

        enqueueAndFinish(taskId, serviceIntent, "subtitle import")
    }


    /**
     * TASK-677 simplify F1: the enqueue outcome tail shared by every dispatch
     * arm (queue-aware started toast, the no-signal Failed toast, cleanup,
     * finish). One site: enqueue policy changes cannot drift between the ASR
     * and subtitle-import paths.
     */
    private fun enqueueAndFinish(taskId: String, serviceIntent: Intent, subject: String) {
        when (InferenceEnqueue.start(this, serviceIntent)) {
            InferenceEnqueue.Outcome.Started,
            InferenceEnqueue.Outcome.FallbackNotificationPosted -> {
                Log.i(TAG, "Enqueued $subject for taskId: $taskId")
                val toastRes = if (InferenceService.isTranscribing.value)
                    R.string.added_to_queue
                else
                    R.string.transcription_started
                com.antivocale.app.util.ToastCompat.show(this, toastRes)
            }
            is InferenceEnqueue.Outcome.Failed -> {
                Log.e(TAG, "Could not enqueue $subject for taskId: $taskId")
                showErrorToast(getString(R.string.transcription_failed))
            }
        }

        endFlow()
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

    /**
     * The flow-end tail shared by every per-file resolution. Outside a batch
     * this is the old cleanup-then-finish; inside a batch it burns one
     * counter slot per resolved file and finishes only on the last, so the
     * sibling URIs keep their read grants and any pending dialog keeps its
     * window.
     */
    private fun endFlow() {
        if (batchFilesRemaining > 0) {
            batchFilesRemaining--
            if (batchFilesRemaining > 0) return
        }
        cleanup()
        finish()
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
