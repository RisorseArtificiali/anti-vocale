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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One WhatsApp voice note as indexed by MediaStore.
 *
 * @property id MediaStore row id (stable dedup key).
 * @property dateAddedSeconds MediaStore DATE_ADDED, in unix seconds (second
 *   granularity: several notes in the same second share the value).
 * @property displayName File name, e.g. `PTT-20260107-WA0000.opus`.
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
     * single WhatsApp write; the CONFLATED channel collapses them into one
     * pending signal, so one query covers a burst.
     */
    sealed interface ChangeSignal {
        data object MediaChanged : ChangeSignal
        data object Disposed : ChangeSignal
    }

    private val _detections = MutableSharedFlow<VoiceNoteEntry>(extraBufferCapacity = 64)
    private val signals = Channel<ChangeSignal>(Channel.CONFLATED)

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
        tracker.absorb(queryWhatsAppVoiceNotes())
        val thread = HandlerThread("VoiceNoteDetector").apply { start() }
        handlerThread = thread
        observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!disposed) signals.trySend(ChangeSignal.MediaChanged)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer!!,
        )
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handlerThread?.quitSafely()
        handlerThread = null
        signals.trySend(ChangeSignal.Disposed)
    }

    /**
     * Coalesced change signals, exposed as a flow so the lifecycle owner
     * (TASK-271) drives collection with its own scope; the detector owns no
     * process-lifetime coroutine (the @ApplicationScope rule). A collector
     * reacts to [ChangeSignal.MediaChanged] by calling [poll].
     */
    fun changeSignals(): Flow<ChangeSignal> = signals.receiveAsFlow()

    /**
     * Run one detection pass: query MediaStore for rows newer than the last
     * seen DATE_ADDED, filter to WhatsApp voice notes, dedup, and emit the new
     * ones on [detections]. Returns the entries emitted this pass.
     */
    suspend fun poll(): List<VoiceNoteEntry> {
        val fresh = tracker.filter(queryWhatsAppVoiceNotes(tracker.watermarkSeconds), selfPackage = context.packageName)
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
                val displayName = cursor.getString(nameCol) ?: continue
                // Cheap pre-filter against the full audio index: only rows that
                // plausibly sit under a WhatsApp voice-note tree advance to the
                // strict filter (which also checks the Opus payload).
                val entry = VoiceNoteEntry(
                    id = cursor.getLong(idCol),
                    dateAddedSeconds = cursor.getLong(dateCol),
                    displayName = displayName,
                    path = if (dataCol >= 0) cursor.getString(dataCol) else null,
                    relativePath = relativePath,
                    mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                    ownerPackage = if (ownerCol >= 0) cursor.getString(ownerCol) else null,
                )
                // The strict rule (location AND Opus payload) is the real gate;
                // the path-only pre-filter above merely spared the allocation.
                if (!VoiceNoteFilter.isWhatsAppVoiceNote(entry)) continue
                entries.add(entry)
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
     * Broad location pre-filter: the row must sit under a WhatsApp tree AND in
     * a "Voice Notes" directory. Case-insensitive; matches both
     * `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/` and the
     * legacy external-storage layout (`WhatsApp/Media/WhatsApp Voice Notes/`),
     * plus localized WhatsApp folder names that keep the literal "Voice Notes"
     * leaf.
     */
    fun looksLikeWhatsAppVoiceNotePath(relativePath: String?, displayName: String): Boolean {
        val haystack = "${relativePath ?: ""}/$displayName".replace('\\', '/')
        return haystack.contains("whatsapp", ignoreCase = true) &&
            haystack.contains("voice notes", ignoreCase = true)
    }

    /**
     * The strict per-entry rule: WhatsApp voice-note location AND an Opus
     * payload. WhatsApp voice notes are .opus; the payload check accepts the
     * audio/opus mime, the audio/ogg container mime some OEM indexers report
     * for .opus, or the .opus extension when MediaStore left the mime
     * unclassified (null or something else entirely).
     */
    fun isWhatsAppVoiceNote(entry: VoiceNoteEntry): Boolean {
        if (listOfNotNull(entry.relativePath, entry.path)
                .none { looksLikeWhatsAppVoiceNotePath(it, entry.displayName) }
        ) {
            return false
        }
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
     * bounded.
     */
    class IncrementalFilter {
        private var lastSeenSeconds = Long.MIN_VALUE
        private var seenIdsAtLastSecond = mutableSetOf<Long>()

        /** The current watermark; queries can bound themselves with `DATE_ADDED >= this`. */
        val watermarkSeconds: Long get() = lastSeenSeconds

        /** Record [entries] as already-known without emitting anything (baseline). */
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
                if (selfPackage != null && entry.ownerPackage == selfPackage) continue
                val isNew = when {
                    entry.dateAddedSeconds > lastSeenSeconds -> true
                    entry.dateAddedSeconds == lastSeenSeconds -> entry.id !in seenIdsAtLastSecond
                    else -> false
                }
                if (!isNew) continue
                if (entry.dateAddedSeconds > lastSeenSeconds) {
                    lastSeenSeconds = entry.dateAddedSeconds
                    seenIdsAtLastSecond = mutableSetOf()
                }
                seenIdsAtLastSecond.add(entry.id)
                fresh.add(entry)
            }
            return fresh
        }
    }
}
