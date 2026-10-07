package com.antivocale.app.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One WhatsApp voice note as indexed by MediaStore.
 *
 * @property id MediaStore row id (stable dedup key).
 * @property dateAddedSeconds MediaStore DATE_ADDED, in unix seconds (second
 *   granularity: several notes in the same second share the value).
 * @property displayName File name, e.g. `PTT-20260107-WA0000.opus`; empty when
 *   MediaStore indexed the row before a name was available (the payload rule
 *   then leans on the mime).
 * @property path Absolute file path (DATA column), when resolvable.
 * @property relativePath Storage-relative directory (API 29+), e.g.
 *   `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/`.
 * @property mimeType MediaStore-classified mime, when known.
 * @property ownerPackage Package that contributed the row (API 29+; null when
 *   the OS did not report it).
 */
data class VoiceNoteEntry(
    val id: Long,
    val dateAddedSeconds: Long,
    val displayName: String,
    val path: String?,
    val relativePath: String?,
    val mimeType: String?,
    val ownerPackage: String?,
)

/**
 * Detection core for incoming WhatsApp voice notes (TASK-270): a passive
 * [ContentObserver] on [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI] that emits
 * NEW WhatsApp voice-note rows as they are indexed. Pure decision logic lives
 * in [VoiceNoteFilter] (unit-tested without an Android device).
 *
 * **Permission requirement (surfaces a task-premise gap):** on API 29+ an app
 * without a read-media permission only sees its OWN contributions when
 * querying MediaStore; WhatsApp's rows are visible only once the runtime
 * permission `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (below 33)
 * has been granted. This app currently declares NO storage permissions
 * (TASK-301 manifest comment: SAF-only); TASK-270 was written under the
 * premise that READ_MEDIA_AUDIO was already granted, and it is NOT in the
 * manifest. Per the task constraints this class does not touch the manifest:
 * TASK-271's lifecycle wiring must request the permission before subscribing.
 * No MANAGE_EXTERNAL_STORAGE, no AccessibilityService, and no permission beyond
 * that standard runtime grant is involved
 * (docs/research/2026-07-12-auto-transcription-feasibility.md, approach 2).
 *
 * **Threading:** [subscribe] runs the baseline index query synchronously, so
 * the TASK-271 lifecycle owner must call it off the main thread. [poll] runs
 * one bounded MediaStore query per call. All state transitions ([subscribe],
 * [dispose], [poll]) serialize on this detector's monitor.
 *
 * **Battery impact:** push-only. No polling loop exists; the MediaStore query
 * runs only when the system reports a change (or when the TASK-271 lifecycle
 * owner explicitly calls [poll]), and it is bounded by `DATE_ADDED >= last
 * seen` so it stays cheap as the index grows. Between voice notes the only
 * live resource is a parked HandlerThread. Callers done with detection must
 * call [dispose] so the thread exits.
 *
 * **Self-delivered files:** rows contributed by this app's own package are
 * skipped, so app-written audio can never re-trigger detection on itself.
 *
 * Detection core only: no service, notification, or lifecycle wiring here
 * (TASK-271 owns that).
 */
@Singleton
class VoiceNoteDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * A change event. MediaStore frequently fires several onChange calls for a
     * single WhatsApp write; the DROP_OLDEST buffer of one collapses them into
     * one pending signal, so one query covers a burst.
     */
    sealed interface ChangeSignal {
        data object MediaChanged : ChangeSignal
        data object Disposed : ChangeSignal
    }

    private val _detections = MutableSharedFlow<VoiceNoteEntry>(extraBufferCapacity = 64)
    // SharedFlow, not a Channel: signals fan out to every collector, and a
    // second (or restarted) collector must never steal the conflated signal.
    private val _signals = MutableSharedFlow<ChangeSignal>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var handlerThread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private var tracker = VoiceNoteFilter.IncrementalFilter()
    private var disposed = false

    /** Hot flow of newly indexed WhatsApp voice notes; never replays old entries. */
    val detections: Flow<VoiceNoteEntry> = _detections

    /**
     * Registers the observer and establishes the baseline: everything already
     * indexed is absorbed silently, so no pre-existing voice note is re-emitted
     * on (re-)subscription. Idempotent while subscribed; a no-op after
     * [dispose] (a disposed detector does not restart, matching the
     * process-lifetime singleton shape).
     */
    @Synchronized
    fun subscribe() {
        if (observer != null || disposed) return
        tracker = VoiceNoteFilter.IncrementalFilter()
        // Baseline first: absorb the existing index before anything can fire.
        // Runs on the caller's thread (see Threading above).
        tracker.absorb(queryWhatsAppVoiceNotes())
        val thread = HandlerThread("VoiceNoteDetector").apply { start() }
        handlerThread = thread
        val registered = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!disposed) _signals.tryEmit(ChangeSignal.MediaChanged)
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                registered,
            )
        } catch (t: Throwable) {
            // A failed registration must not strand the parked thread.
            handlerThread = null
            thread.quitSafely()
            throw t
        }
        observer = registered
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handlerThread?.quitSafely()
        handlerThread = null
        _signals.tryEmit(ChangeSignal.Disposed)
    }

    /**
     * Coalesced change signals, exposed as a shared flow so any number of
     * collectors (the TASK-271 lifecycle owner drives collection with its own
     * scope; the detector owns no process-lifetime coroutine, the
     * @ApplicationScope rule) each see the events. A collector reacts to
     * [ChangeSignal.MediaChanged] by calling [poll].
     */
    fun changeSignals(): Flow<ChangeSignal> = _signals.asSharedFlow()

    /**
     * Run one detection pass: query MediaStore for rows at or after the last
     * seen DATE_ADDED, filter to WhatsApp voice notes, dedup, and emit the new
     * ones on [detections]. Returns the entries emitted this pass.
     */
    suspend fun poll(): List<VoiceNoteEntry> {
        val fresh: List<VoiceNoteEntry>
        synchronized(this) {
            val candidates = queryWhatsAppVoiceNotes(tracker.watermarkSeconds)
            val (voiceNotes, rejected) =
                candidates.partition { VoiceNoteFilter.isWhatsAppVoiceNote(it) }
            fresh = tracker.filter(voiceNotes, selfPackage = context.packageName)
            // Candidates that failed the strict rule are still recorded: a
            // MediaStore re-index that fills in a missing name or mime in the
            // same second must not surface as a fresh detection.
            tracker.absorb(rejected)
        }
        fresh.forEach { _detections.emit(it) }
        return fresh
    }

    private fun queryWhatsAppVoiceNotes(minDateAdded: Long = Long.MIN_VALUE): List<VoiceNoteEntry> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)
            }
        }
        val entries = mutableListOf<VoiceNoteEntry>()
        // Bounded query: only rows at or after the watermark can ever be new
        // (same-second rows must come back for the id-set dedup, hence >=).
        val selection = if (minDateAdded != Long.MIN_VALUE) {
            "${MediaStore.Audio.Media.DATE_ADDED} >= ?"
        } else {
            null
        }
        val selectionArgs = if (minDateAdded != Long.MIN_VALUE) arrayOf(minDateAdded.toString()) else null
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            /* selection = */ selection,
            /* selectionArgs = */ selectionArgs,
            /* sortOrder = */ "${MediaStore.Audio.Media.DATE_ADDED} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val ownerCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val relativePath = if (relPathCol >= 0) cursor.getString(relPathCol) else null
                val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                // Cheap location pre-filter over the DIRECTORY components of
                // the row: a display name like "WhatsApp Voice Notes - demo"
                // must never confer WhatsApp-ness on a foreign folder. Rows
                // that pass advance to the strict per-entry rule in [poll].
                val directories = listOfNotNull(
                    relativePath,
                    path?.substringBeforeLast('/', missingDelimiterValue = ""),
                )
                if (directories.none { VoiceNoteFilter.isWhatsAppVoiceNoteDirectory(it) }) continue
                entries.add(
                    VoiceNoteEntry(
                        id = cursor.getLong(idCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        displayName = cursor.getString(nameCol) ?: "",
                        path = path,
                        relativePath = relativePath,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                        ownerPackage = if (ownerCol >= 0) cursor.getString(ownerCol) else null,
                    ),
                )
            }
        }
        return entries
    }
}

/**
 * Pure decision logic for voice-note detection, split out of
 * [VoiceNoteDetector] so the rules are unit-testable without a device.
 */
object VoiceNoteFilter {
    /**
     * Location rule: the row's DIRECTORY must sit under a WhatsApp tree AND in
     * a "Voice Notes" directory. Case-insensitive; matches both
     * `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/` and the
     * legacy external-storage layout (`WhatsApp/Media/WhatsApp Voice Notes/`),
     * plus localized WhatsApp folder names that keep the literal "Voice Notes"
     * leaf. The file name is deliberately NOT part of the haystack: a podcast
     * named "whatsapp-voice-notes-episode.opus" in Music/ is not a WhatsApp
     * note.
     */
    fun isWhatsAppVoiceNoteDirectory(directory: String?): Boolean {
        if (directory == null) return false
        val normalized = directory.replace('\\', '/')
        return normalized.contains("whatsapp", ignoreCase = true) &&
            normalized.contains("voice notes", ignoreCase = true)
    }

    /**
     * The strict per-entry rule: WhatsApp voice-note location AND an Opus
     * payload. WhatsApp voice notes are .opus; the payload check accepts the
     * audio/opus mime, the audio/ogg container mime some OEM indexers report
     * for .opus, or the .opus extension when MediaStore left the mime
     * unclassified (null or something else entirely).
     */
    fun isWhatsAppVoiceNote(entry: VoiceNoteEntry): Boolean {
        val directories = listOfNotNull(
            entry.relativePath,
            entry.path?.substringBeforeLast('/', missingDelimiterValue = ""),
        )
        if (directories.none { isWhatsAppVoiceNoteDirectory(it) }) return false
        return when (entry.mimeType?.lowercase()) {
            "audio/opus", "audio/ogg" -> true
            else -> entry.displayName.endsWith(".opus", ignoreCase = true)
        }
    }

    /**
     * Stateful, deterministic incrementality: decides which query results are
     * NEW relative to everything already observed. Two defenses do the work:
     *
     * - a `DATE_ADDED > lastSeenSeconds` watermark (MediaStore's DATE_ADDED has
     *   second granularity, so entries in the same second as the watermark are
     *   candidates, not auto-passes);
     * - an id set covering the watermark second, so a re-indexed row (same id,
     *   same second) is never emitted twice, while a genuinely new row with the
     *   same DATE_ADDED second still gets through.
     *
     * Rows outside the watermark second never consult the id set, so it stays
     * bounded. Every observed row (self-contributed, already seen, or
     * rejected) still advances the watermark and the id set, keeping the poll
     * query window tight.
     */
    class IncrementalFilter {
        private var lastSeenSeconds = Long.MIN_VALUE
        private var seenIdsAtLastSecond = mutableSetOf<Long>()

        /** The current watermark; queries can bound themselves with `DATE_ADDED >= this`. */
        val watermarkSeconds: Long get() = lastSeenSeconds

        /** Record [entries] as already-known without emitting anything (baseline, rejected candidates). */
        fun absorb(entries: List<VoiceNoteEntry>) {
            filter(entries, selfPackage = null)
        }

        /**
         * Return the subset of [entries] that is new and not contributed by
         * [selfPackage] (null disables the self check, e.g. during [absorb]),
         * updating the watermark and dedup state.
         */
        fun filter(entries: List<VoiceNoteEntry>, selfPackage: String?): List<VoiceNoteEntry> {
            val fresh = mutableListOf<VoiceNoteEntry>()
            for (entry in entries.sortedBy { it.dateAddedSeconds }) {
                if (entry.dateAddedSeconds > lastSeenSeconds) {
                    lastSeenSeconds = entry.dateAddedSeconds
                    seenIdsAtLastSecond = mutableSetOf()
                }
                if (entry.dateAddedSeconds < lastSeenSeconds) continue
                val isSelf = selfPackage != null && entry.ownerPackage == selfPackage
                if (!isSelf && entry.id !in seenIdsAtLastSecond) fresh.add(entry)
                seenIdsAtLastSecond.add(entry.id)
            }
            return fresh
        }
    }
}
