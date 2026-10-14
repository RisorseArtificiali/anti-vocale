package com.antivocale.app.data

import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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
class VoiceNoteDetector @Inject constructor() {

    private val _detections = Channel<VoiceNoteEntry>(Channel.UNLIMITED)

    /** Every new voice note, already deduped; queue semantics, no loss. */
    val detections: Flow<VoiceNoteEntry> = _detections.receiveAsFlow()

    private val seen = VoiceNoteFilter.IncrementalFilter()
    private val observers = mutableListOf<FileObserver>()
    private val watchedDirs = mutableSetOf<String>()

    // Guards watch() from observer threads too: without it, a late watch
    // (a counter CREATE racing dispose) could add an observer AFTER the
    // teardown cleared the list, and the next subscribe would short-
    // circuit on the stale entry, silently disarming detection.
    private var armed = false

    val root: File
        get() = voiceNotesRoot()

    @Synchronized
    fun subscribe() {
        if (observers.isNotEmpty()) return
        armed = true
        watch(root)
        root.listFiles()
            ?.filter { VoiceNoteFilter.isCounterFolderName(it.name) }
            ?.forEach { watch(it) }
        Log.i(TAG, "subscribed: ${observers.size} watchers under ${root.absolutePath}")
    }

    @Synchronized
    fun dispose() {
        armed = false
        observers.forEach { it.stopWatching() }
        observers.clear()
        watchedDirs.clear()
        Log.i(TAG, "disposed")
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
                    watch(File(dir, path))
                    return
                }
                // File notes only once the write is complete (see class
                // KDoc): CREATE alone means WhatsApp is still writing.
                if ((event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO)) == 0) return
                if (!VoiceNoteFilter.isWhatsAppVoiceNoteName(path)) return
                val file = File(dir, path)
                onDetection(VoiceNoteEntry(
                    id = file.absolutePath.hashCode().toLong(),
                    path = file.absolutePath,
                    detectedAtMs = System.currentTimeMillis()))
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

    private fun onDetection(entry: VoiceNoteEntry) {
        val isNew = synchronized(this@VoiceNoteDetector) { seen.observe(entry) }
        if (isNew) _detections.trySend(entry)
    }

    private companion object {
        const val TAG = "VoiceNoteDetector"
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
