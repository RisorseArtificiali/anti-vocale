package com.antivocale.app.data

import android.os.Environment
import android.os.FileObserver
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.antivocale.app.di.ApplicationScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One detected WhatsApp voice note (TASK-271, reworked 2026-10-13 onto
 * FileObserver after the MediaStore premise died on device).
 *
 * @property id Path-hash (stable dedup key; replaces the MediaStore row id).
 * @property path Absolute file path as observed (unreadable directly: the
 *   tap path resolves it through the SAF tree grant).
 * @property detectedAtMs When the observer fired. Stamped at emission, not
 *   at consumption: the serial collector can sit behind a resolve or a
 *   label wait, and the sender match needs the EVENT's time (its
 *   candidate window rejects notifications posted far from it, TASK-821).
 */
data class VoiceNoteEntry(
    val id: Long,
    val path: String?,
    val detectedAtMs: Long,
)

/**
 * Detection core for incoming WhatsApp voice notes (TASK-270 origin,
 * reworked 2026-10-13): [FileObserver]s over the WhatsApp Voice Notes
 * tree emit each new .opus file the moment WhatsApp finishes writing it.
 * The observer route is the inotify side channel documented by the File
 * Notification Attacks research (CCS 2026, demonstrated against
 * WhatsApp): events cross the FUSE per-app view that blocks listing
 * level 2 and direct opens. FRAGILITY NOTE: that channel is a documented
 * vulnerability; if Android closes it, detection dies silently (the
 * Settings copy and the task record both say so).
 *
 * Watchers cover the tree root (new counter folders) and each existing
 * counter folder (level-1 enumeration works with READ_MEDIA_AUDIO, the
 * spike's probe 1). [detections] is a command queue, not a broadcast:
 * every detected note must reach the transcriber exactly once (TASK-272
 * AC3), so the Channel is unbounded and emission never drops (entries
 * are small fixed-shape values; WhatsApp cannot outgrow memory between
 * the consumer's serial resolves). File notes fire on CLOSE_WRITE/MOVED_TO
 * ONLY (never CREATE: the automatic form would copy the file while
 * WhatsApp is still writing it, and the dedup would then swallow the
 * CLOSE_WRITE that proves the write complete); CREATE stays in the mask
 * for new counter FOLDERS.
 */
@Singleton
class VoiceNoteDetector @Inject constructor(
    // The process-lifetime scope (carries the CrashReporter handler; never
    // cancelled): the catch-up delay rides it instead of a private executor,
    // per the TASK-438 rule against hand-built concurrency owners.
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _detections = Channel<VoiceNoteEntry>(Channel.UNLIMITED)

    /** Every new voice note, already deduped; queue semantics, no loss. */
    val detections: Flow<VoiceNoteEntry> = _detections.receiveAsFlow()

    private val seen = VoiceNoteFilter.IncrementalFilter()
    // vars: refresh() swaps in fresh collections gap-free (review F2)
    private var observers = mutableListOf<FileObserver>()
    private var watchedDirs = mutableSetOf<String>()

    // Guards watch() from observer threads too: without it, a late watch
    // (a counter CREATE racing dispose) could add an observer AFTER the
    // teardown cleared the list, and the next subscribe would short-
    // circuit on the stale entry, silently disarming detection.
    // Volatile: catchUpScan reads it outside the monitor (its listing is
    // lock-free; see there).
    @Volatile
    private var armed = false

    /**
     * TASK-831: counter folders with a catch-up scan scheduled or
     * orphaned. Folder creation fires CREATE and MOVED_TO (plus touches),
     * so the schedule site would otherwise stack duplicate scans; one
     * pending entry per folder collapses them. Removed by the scan itself
     * once it finds no fresh files, so a later re-fire can schedule
     * again; a refresh never schedules catch-ups (it re-watches folders,
     * and this scan covers only the folder-creation gap), and a disarm
     * leaves the entry for subscribe() to relaunch.
     */
    private val pendingCatchUps = mutableSetOf<String>()

    val root: File
        get() = voiceNotesRoot()

    @Synchronized
    fun subscribe() {
        if (observers.isNotEmpty()) return
        armWatchers()
        // TASK-831: a disarm inside a catch-up's delay window leaves its
        // pending entry orphaned (the scan ran disarmed and emitted
        // nothing); the re-arm relaunches those instead of losing the
        // note forever. A doubled launch in the armed-but-root-missing
        // corner is a seen-filter no-op.
        pendingCatchUps.toList().forEach { launchCatchUp(File(it)) }
        Log.i(TAG, "subscribed: ${observers.size} watchers under ${root.absolutePath}")
    }

    /** The watcher enumeration both subscribe() and refresh() ride. */
    private fun armWatchers() {
        armed = true
        watch(root)
        root.listFiles()
            ?.filter { VoiceNoteFilter.isCounterFolderName(it.name) }
            ?.forEach { watch(it) }
    }

    @Synchronized
    fun dispose() {
        armed = false
        observers.forEach { it.stopWatching() }
        observers.clear()
        watchedDirs.clear()
        Log.i(TAG, "disposed")
    }

    /**
     * TASK-830: the blind periodic re-subscribe. The internal state lies
     * after storage churn (watchers register as live while delivering
     * nothing; the 2026-10-14 device evidence), so the cure is
     * re-subscribing, not checking. GAP-FREE: the fresh observers start
     * BEFORE the old ones stop (review F2: a stop-then-start swap would
     * open a recurring event-loss window on healthy channels; the brief
     * overlap is safe because the `seen` filter dedups the double
     * delivery). A no-op when not armed: a refresh can never resurrect a
     * disarmed detector. Notes that arrived while the channel was dead are
     * NOT recovered here or anywhere (their events never fired; the
     * TASK-831 catch-up scan is scoped to NEW counter folders, not
     * existing ones): this cadence is what bounds that loss window.
     */
    @Synchronized
    fun refresh() {
        if (!armed) return
        val oldObservers = observers
        observers = mutableListOf()
        watchedDirs = mutableSetOf()
        // finally (review 2026-10-15): armWatchers can throw mid-swap under
        // the very storage churn this cadence survives; without the finally,
        // the swapped-out observers leak forever (dispose cannot reach them
        // anymore) and every failed tick leaks another full set.
        try {
            armWatchers()
        } finally {
            oldObservers.forEach { it.stopWatching() }
        }
        Log.i(TAG, "refreshed: ${observers.size} watchers (gap-free)")
    }

    @Synchronized
    private fun watch(dir: File) {
        if (!armed) return
        // CREATE and MOVED_TO both fire for one folder creation, and folder
        // touches re-fire: the watched-set keeps one observer per directory.
        if (!watchedDirs.add(dir.absolutePath)) return
        val observer = object : FileObserver(
            // The String constructor: FileObserver(File, Int) is API 29+
            // and detection runs down to minSdk 26 (lint-fatal, CI 2026-10-14).
            dir.absolutePath,
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                // Field diagnosis of the write pattern (which mask bits the
                // writers actually emit through the FUSE view): opt-in via
                // `setprop log.tag.VoiceNoteDetector VERBOSE`.
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "event=0x${Integer.toHexString(event)} path=$path dir=${dir.name}")
                }
                // A new counter folder at the root: watch it too (events
                // for its files do not bubble up).
                if (VoiceNoteFilter.isCounterFolderName(path) &&
                    (event and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0
                ) {
                    val folder = File(dir, path)
                    watch(folder)
                    // TASK-831: notes WhatsApp wrote between the folder's
                    // creation and the watch attaching never fire an event
                    // (the inotify-delivery gap); one DELAYED catch-up scan
                    // emits them through the same dedup path. The scan only
                    // emits QUIET files (see catchUpScan), so a note still
                    // being written waits for a later round; its own
                    // CLOSE_WRITE emits it first if the watch caught that.
                    scheduleCatchUp(folder)
                    return
                }
                // File notes only once the write is complete (see class
                // KDoc): CREATE alone means WhatsApp is still writing.
                if ((event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO)) == 0) return
                if (!VoiceNoteFilter.isWhatsAppVoiceNoteName(path)) return
                onDetection(entryFor(File(dir, path)))
            }
        }
        // A missing directory (WhatsApp cleared or reinstalled) must not
        // count as a watcher: FileObserver.startWatching() returns nothing,
        // so the existence check carries the accounting. An empty observers
        // list lets the next reconcile retry the subscribe when the folder
        // is back.
        if (dir.isDirectory) {
            observer.startWatching()
            observers.add(observer)
        } else {
            watchedDirs.remove(dir.absolutePath)
            Log.w(TAG, "not watching missing ${dir.absolutePath}")
        }
    }

    /**
     * TASK-831: schedules the folder's delayed catch-up scan, deduped by
     * [pendingCatchUps]. Runs on the observer callback thread. Internal for
     * the unit test (the real event cannot fire under Robolectric).
     */
    @VisibleForTesting
    @Synchronized
    internal fun scheduleCatchUp(folder: File) {
        if (!armed) return
        if (!pendingCatchUps.add(folder.absolutePath)) return
        launchCatchUp(folder)
    }

    /** The scan's one delay, kept as a field for the test seam (the
     *  coordinator's rearmIntervalMs precedent; a defaulted constructor
     *  param would break Dagger). */
    @VisibleForTesting
    @Volatile
    internal var catchUpDelayMs = CATCH_UP_DELAY_MS

    private fun launchCatchUp(folder: File) {
        scope.launch(Dispatchers.IO) {
            delay(catchUpDelayMs)
            catchUpScan(folder)
        }
    }

    @Synchronized
    private fun clearCatchUp(folder: File) {
        pendingCatchUps.remove(folder.absolutePath)
    }

    /** Test seam: folders with a catch-up scheduled or orphaned. */
    @VisibleForTesting
    @Synchronized
    internal fun pendingCatchUpsCount(): Int = pendingCatchUps.size

    /**
     * TASK-831: emits every unseen QUALIFYING and QUIET note in [folder].
     * Quiet means no write for [catchUpDelayMs]: name filtering alone
     * cannot tell a complete note from one still being written, and
     * emitting a partial copy would burn the seen filter's dedup on it
     * (the observer's later CLOSE_WRITE for the complete file would then
     * be swallowed). Fresh files force one more round instead. The seen
     * filter absorbs both the observer's own delivery of the same note
     * and the gap-free refresh overlap, so no path double-fires.
     */
    @VisibleForTesting
    internal fun catchUpScan(folder: File) {
        // Disarmed: keep the pending entry (subscribe() relaunches it on
        // re-arm); dropping it here would permanently lose the very note
        // this scan exists to recover.
        if (!armed) return
        val now = System.currentTimeMillis()
        // FUSE listing OUTSIDE the monitor: under exactly the storage
        // churn this scan targets, a ~100-entry listing can stall, and
        // holding the monitor would block every observer callback plus
        // subscribe/dispose/refresh for the whole listing. Emission and
        // bookkeeping re-take it (onDetection, clearCatchUp).
        val notes = folder.listFiles()
            ?.filter { it.isFile && VoiceNoteFilter.isWhatsAppVoiceNoteName(it.name) }
            .orEmpty()
        val ripe = notes.filter { now - it.lastModified() >= catchUpDelayMs }
        if (ripe.isNotEmpty()) {
            Log.i(TAG, "catch-up emitted ${ripe.size} note(s) from ${folder.name}")
            ripe.forEach { file -> onDetection(entryFor(file)) }
        }
        if (ripe.size == notes.size) {
            clearCatchUp(folder)
        } else {
            // Fresh files: another round once the youngest goes quiet
            // (the entry stays pending; launchCatchUp does not re-gate).
            launchCatchUp(folder)
        }
    }

    /**
     * One queue entry from an observed file. The id derivation is the
     * stable dedup key ([VoiceNoteEntry.id]); every construction site MUST
     * go through here, or a drifted key silently double-fires notes the
     * seen filter was built to dedup.
     */
    private fun entryFor(file: File) = VoiceNoteEntry(
        id = file.absolutePath.hashCode().toLong(),
        path = file.absolutePath,
        detectedAtMs = System.currentTimeMillis())

    private fun onDetection(entry: VoiceNoteEntry) {
        val isNew = synchronized(this@VoiceNoteDetector) { seen.observe(entry) }
        if (isNew) _detections.trySend(entry)
    }

    private companion object {
        const val TAG = "VoiceNoteDetector"

        /**
         * TASK-831: long enough that a note mid-write at folder-creation
         * time completes first (WhatsApp writes land in well under a
         * second); short enough that the catch-up still feels instant.
         */
        const val CATCH_UP_DELAY_MS = 2_000L
    }
}

/** The canonical WhatsApp Voice Notes folder (the detector's watch root and
 *  the Settings picker's initial location: one owner of the path). */
fun voiceNotesRoot(): File =
    File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes")

/**
 * The pure decision rules for voice-note detection, split out of
 * [VoiceNoteDetector] so they stay unit-testable without a device.
 */
object VoiceNoteFilter {

    /**
     * WhatsApp's voice-note file naming: PTT-YYYYMMDD-WANNNNN.opus.
     * Covers sent and received notes alike (both land in the tree).
     */
    fun isWhatsAppVoiceNoteName(name: String): Boolean =
        name.startsWith("PTT-", ignoreCase = true) &&
            name.endsWith(".opus", ignoreCase = true)

    /** WhatsApp's rolling counter folders (one per ~100 notes, numeric). */
    fun isCounterFolderName(name: String): Boolean = name.toIntOrNull() != null

    /**
     * Event dedup: the same file can fire CLOSE_WRITE more than once
     * (WhatsApp's post-write touches); a bounded recent-id window keeps
     * one queue entry per note while the memory stays constant.
     */
    class IncrementalFilter(private val capacity: Int = 128) {
        private val seen = object : LinkedHashMap<Long, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>) =
                size > capacity
        }

        /** true when the entry is NEW (first sighting within the window). */
        fun observe(entry: VoiceNoteEntry): Boolean =
            seen.put(entry.id, Unit) == null
    }
}
