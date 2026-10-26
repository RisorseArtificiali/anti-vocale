package com.antivocale.app.data

import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.antivocale.app.di.ApplicationScope
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /**
     * TASK-778: true when the entry came from the cold-start catch-up
     * sweep (the note arrived while the process was dead) rather than a
     * live observer event. Downstream treats both identically in v1 (the
     * enqueue-side 842 dedup makes a false positive cheap); the flag is
     * the seam the burst-digest notification will key on.
     */
    val catchup: Boolean = false,
)

/**
 * TASK-906: one file the catch-up sweep is considering, decoupled from
 * java.io: the SAF lane cannot stat (the provider cursor carries the
 * mtime), so [lastModified] travels WITH the candidate instead of being
 * read off the File. [path] is the RAW absolute path (the entry id, the
 * watermark key input, and the transcriber's SAF-resolve key all
 * consume exactly this string).
 */
internal data class SweepFile(
    val path: String,
    val lastModified: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

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
 *
 * One instance per [VoiceNoteSource] (WhatsApp Business support): each
 * watches its own client's tree, with its own seen filter and catch-up
 * bookkeeping, so arming or disposing one never touches the other. Not
 * injected directly: AppModule builds one per source next to its
 * coordinator.
 */
class VoiceNoteDetector(
    // The process-lifetime scope (carries the CrashReporter handler; never
    // cancelled): the catch-up delay rides it instead of a private executor,
    // per the TASK-438 rule against hand-built concurrency owners.
    @ApplicationScope private val scope: CoroutineScope,
    // TASK-906: for the SAF sweep lane's DocumentsContract queries (the
    // raw java.io listing is denied to the app uid on scoped storage).
    private val appContext: android.content.Context,
    // TASK-778: the persisted watermark this detector reads at arm and
    // advances on every emission.
    private val preferencesManager: PreferencesManager,
    val source: VoiceNoteSource = VoiceNoteSource.WHATSAPP,
) {

    private val _detections = Channel<VoiceNoteEntry>(Channel.UNLIMITED)

    /** Every new voice note, already deduped; queue semantics, no loss. */
    val detections: Flow<VoiceNoteEntry> = _detections.receiveAsFlow()

    /**
     * TASK-906: the coordinator's live tree grant, handed over at every
     * arm. Non-null flips the catch-up sweep onto the SAF listing lane:
     * on scoped storage the app's raw java.io view of the shared tree is
     * denied device-wide (listFiles returns null), while the grant's
     * DocumentsContract queries demonstrably read the same tree (the
     * transcription resolve path has always ridden them).
     */
    @Volatile private var grantedTreeUri: String? = null

    /**
     * TASK-889: the cold-start catch-up sweep's burst size, emitted once
     * per sweep round that found candidates (the count the digest
     * notification names). The detector reports the fact; the coordinator
     * owns the presentation. A Channel like [detections] above: the value
     * must survive from emission to the coordinator's collector whatever
     * the subscribe ordering, which a replay-0 SharedFlow cannot promise
     * (tryEmit drops when no subscriber is attached yet).
     */
    private val _catchUpBursts = Channel<Int>(Channel.BUFFERED)
    val catchUpBursts: Flow<Int> = _catchUpBursts.receiveAsFlow()

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
        get() = voiceNotesRoot(source)

    @Synchronized
    fun subscribe(treeUri: String? = null) {
        // Before the idempotence early-return (review F5): a re-subscribe
        // with a fresh grant must update the SAF lane even when watchers
        // are already attached, and the URI survives until replaced.
        if (treeUri != null) grantedTreeUri = treeUri
        if (observers.isNotEmpty()) return
        armWatchers()
        // TASK-831: a disarm inside a catch-up's delay window leaves its
        // pending entry orphaned (the scan ran disarmed and emitted
        // nothing); the re-arm relaunches those instead of losing the
        // note forever. A doubled launch in the armed-but-root-missing
        // corner is a seen-filter no-op.
        pendingCatchUps.toList().forEach { launchCatchUp(File(it)) }
        Log.i(TAG, "subscribed ${source.name}: ${observers.size} watchers under ${root.absolutePath}")
        launchColdCatchUp()
    }

    /**
     * TASK-778 (829 option C): the watermark-gated recovery sweep, fired at
     * every arm AND every [refresh] tick. Notes that arrived while the
     * process was dead OR while a watch channel was silently dead (the
     * documented 830 boundary, healed by the blind rearm) have no observer
     * events waiting; the persisted watermark is the only thing that can
     * tell them from pre-existing history. On the app scope, outside the
     * monitor: the sweep lists the FUSE tree, which can stall (the 831
     * outside-the-monitor rule), and steady-state refreshes pay one
     * listing plus zero stats (the watermark key filter runs before any
     * lastModified call).
     */
    private fun launchColdCatchUp() {
        Log.i(TAG, "cold catch-up launched")
        scope.launch(Dispatchers.IO) { coldCatchUp() }
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
        grantedTreeUri = null
        armed = false
        observers.forEach { it.stopWatching() }
        observers.clear()
        watchedDirs.clear()
        Log.i(TAG, "disposed ${source.name}")
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
     * disarmed detector. TASK-778 widened the recovery: notes that arrived
     * while a channel was dead are picked up by the watermark-gated cold
     * sweep this tick also fires ([launchColdCatchUp]).
     */
    @Synchronized
    fun refresh(treeUri: String? = null) {
        if (!armed) return
        // TASK-906 (review altitude pass): the tick must track the LIVE
        // grant like the resolve path does: after a live-to-live re-pick
        // the short-circuit path never re-subscribes, so the URI rides
        // every refresh too or the SAF walk keeps dying on the released
        // grant until a full re-arm. Null hand = no new information.
        if (treeUri != null) grantedTreeUri = treeUri
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
        Log.i(TAG, "refreshed ${source.name}: ${observers.size} watchers (gap-free)")
        // TASK-778: this tick also fires the watermark-gated cold sweep, so
        // a note whose event fired into a dead channel between ticks is
        // recovered at the NEXT tick, not only at the next process restart.
        launchColdCatchUp()
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
                onDetection(entryFor(File(dir, path).absolutePath))
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

    /** TASK-778 test seams (the rearmIntervalMs/catchUpDelayMs precedent):
     * the cold-start catch-up TTL (notes older than this are stale: a
     * 3-day-old note transcribed at 07:00 is rarely wanted, and WhatsApp
     * notes also arrive while the user is IN WhatsApp reading them) and
     * the burst cap (newest K only).
     */
    @VisibleForTesting
    @Volatile
    internal var coldCatchUpTtlMs = COLD_CATCH_UP_TTL_MS

    @VisibleForTesting
    @Volatile
    internal var coldCatchUpCap = COLD_CATCH_UP_CAP

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
            ripe.forEach { file -> onDetection(entryFor(file.absolutePath)) }
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
     * One queue entry from an observed file path. The id derivation is the
     * stable dedup key ([VoiceNoteEntry.id]); every construction site MUST
     * go through here, or a drifted key silently double-fires notes the
     * seen filter was built to dedup. TASK-906: keyed on the PATH STRING,
     * not java.io (the SAF sweep lane synthesizes paths it never stats).
     */
    private fun entryFor(path: String) = VoiceNoteEntry(
        id = path.hashCode().toLong(),
        path = path,
        detectedAtMs = System.currentTimeMillis())

    /** True iff this entry passed the seen filter and was emitted (the
     *  sweep burst counts these, so a live-delivered or deduped note never
     *  inflates the digest; TASK-889). */
    private fun onDetection(entry: VoiceNoteEntry): Boolean {
        val isNew = synchronized(this@VoiceNoteDetector) { seen.observe(entry) }
        if (isNew) {
            _detections.trySend(entry)
            advanceWatermark(entry)
            return true
        }
        return false
    }

    /**
     * TASK-778: this source's persisted watermark advances to the newest
     * EMITTED note on every emission (live and catch-up alike). The
     * compare lives inside the store's bump (monotonic; concurrent
     * emissions cannot regress it). Fire-and-forget on the app scope: a
     * save lost to process death at worst re-emits a note the 842 enqueue
     * dedup then absorbs.
     */
    private fun advanceWatermark(entry: VoiceNoteEntry) {
        val key = VoiceNoteFilter.watermarkKeyOf(File(entry.path ?: return)) ?: return
        scope.launch(Dispatchers.IO) {
            preferencesManager.bumpVoiceNoteWatermark(source, key)
        }
    }

    /**
     * TASK-778 (with the 829 option-C maintainer decision): scan the
     * EXISTING counter folders once per arm and emit the notes newer than
     * this source's persisted watermark, younger than [coldCatchUpTtlMs], capped at
     * the newest [coldCatchUpCap]. This is the recovery path for notes
     * that arrived while the process was dead or a watch channel was
     * silently dead (the documented 830/831 boundary): their events never
     * fired, so nothing else can see them. False-positive emission is
     * cheap (the enqueue-side 842 dedup stamps a content hash on an
     * existing SUCCESS row), so the TTL and cap are UX dials bounding the
     * cold-start burst, not correctness guards.
     */
    private suspend fun coldCatchUp() {
        Log.i(TAG, "cold catch-up running (armed=$armed)")
        if (!armed) return
        val treeUri = grantedTreeUri
        if (treeUri != null) {
            // TASK-906: the SAF lane is the PRODUCTION lane. Measured on
            // the RMX3853 (2026-10-26): the raw ROOT enumeration works
            // (armWatchers attaches its watchers from it) but the child
            // folders' raw listings come back EMPTY, so the sweep found
            // nothing; the tree grant's DocumentsContract queries read
            // those same children (the lane the transcription resolve
            // path has always ridden).
            coldCatchUpViaSaf(treeUri)
            return
        }
        val folders = root.listFiles()
            ?.filter { it.isDirectory && VoiceNoteFilter.isCounterFolderName(it.name) }
            .orEmpty()
        Log.i(TAG, "cold catch-up folders=${folders.size}")
        coldCatchUpScan(folders)
    }

    /** The SAF walk's result: per-folder files plus the folder docIds the
     *  settle re-stat needs for its single-folder query. */
    internal data class SafWalk(
        val filesByFolder: Map<String, List<SweepFile>>,
        val folderDocIds: Map<String, String>,
    )

    /** Test seam for the SAF lane: replaces the DocumentsContract walk
     *  (Robolectric has no documents provider) while keeping the lane
     *  selection and the core real. The floor mirrors the production
     *  pre-walk prune so tests can exercise both shapes. */
    internal var safWalkForTest:
        (suspend (treeUri: String, watermarkFolder: Long?) -> SafWalk?)? = null

    private suspend fun coldCatchUpViaSaf(treeUri: String) {
        // Review F5: the settle round stats every fresh file; a per-folder
        // cache turns N files in one folder into ONE children query. Keyed
        // WITH the live URI (review F3): after a re-pick inside the settle
        // window, the first stat under the new grant must not serve maps
        // captured from the released one.
        val statCache = HashMap<String, Map<String, Long>>()
        fun statCacheKey(uri: String, folder: String) = "$uri|$folder"
        // The floor (review efficiency pass): level-1 names are ONE query;
        // only folders at or above the watermark's folder pay their
        // (binder) level-2 query. Steady state walks root + one folder
        // instead of the whole tree, at every arm and every rearm tick.
        val watermark = preferencesManager.voiceNoteWatermark(source).first()
        val floor = watermark?.substringBefore('/')?.toLongOrNull()
        val walk = safWalkForTest?.invoke(treeUri, floor) ?: safWalkTree(treeUri, floor)
        if (walk == null) {
            Log.w(TAG, "cold catch-up (SAF): folder walk failed for ${source.name}")
            return
        }
        val folders = walk.filesByFolder.keys.filter { VoiceNoteFilter.isCounterFolderName(it) }
        Log.i(TAG, "cold catch-up (SAF lane) folders=${folders.size}")
        scanCandidates(
            folderNames = folders,
            allowRetry = true,
            listFiles = { folderName -> walk.filesByFolder[folderName] },
            stat = { path ->
                // The settle re-stat: ONE children query on the file's own
                // folder (the walk's docIds), never a tree re-walk. The
                // provider is the only stat authority on this lane, and the
                // URI is read at STAT time (latest-wins: a re-pick inside
                // the settle window must not query the released grant).
                val folder = path.substringBeforeLast('/').substringAfterLast('/')
                val name = path.substringAfterLast('/')
                val docId = walk.folderDocIds[folder] ?: return@scanCandidates null
                // Latest-wins URI (review F3): a re-pick inside the settle
                // window released the captured grant; the LIVE one is the
                // only URI the provider will answer for.
                val liveUri = grantedTreeUri ?: treeUri
                statCache.getOrPut(statCacheKey(liveUri, folder)) {
                    safListFolderMtimes(liveUri, docId)
                }[name]
            })
    }

    /** One folder's current name-to-mtime map via a single children
     *  query (the settle round's per-folder cache fills from this). */
    private fun safListFolderMtimes(treeUri: String, folderDocId: String): Map<String, Long> =
        runCatching {
            val tree = Uri.parse(treeUri)
            val out = mutableMapOf<String, Long>()
            appContext.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(1)) out[c.getString(0)] = c.getLong(1)
                }
            }
            out
        }.getOrDefault(emptyMap())

    /**
     * TASK-906: two-level DocumentsContract walk over the held tree
     * grant (counter folders at level 1, their files at level 2), mapped
     * back onto RAW absolute paths so the sweep core, the entry ids and
     * the transcriber's grant-resolve key stay byte-identical to the raw
     * lane's. Returns null when the walk itself fails (never an empty
     * guess: an unreadable tree is a fact to log, not "no notes").
     * [watermarkFolder] pre-prunes the level-2 queries (steady state
     * walks root + one folder); when NO counter folder clears the floor
     * the walk lists EVERYTHING so scanCandidates' reset branch can fire
     * and recover in the same round (the raw lane's semantics).
     * A row without COLUMN_LAST_MODIFIED is skipped and logged (an
     * undatable file cannot pass the quiet gate; fabricating an mtime
     * either bypassed the gate or died in the settle's re-stat).
     */
    private fun safWalkTree(
        treeUri: String,
        watermarkFolder: Long?,
    ): SafWalk? = runCatching {
        val tree = Uri.parse(treeUri)
        val resolver = appContext.contentResolver
        val folderDocIds = mutableMapOf<String, String>()
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    VoiceNoteFilter.isCounterFolderName(c.getString(1))
                ) {
                    folderDocIds[c.getString(1)] = c.getString(0)
                }
            }
        } ?: return@runCatching null
        val toWalk = if (watermarkFolder != null &&
            folderDocIds.keys.any { (it.toLongOrNull() ?: -1L) >= watermarkFolder }
        ) {
            folderDocIds.filterKeys { (it.toLongOrNull() ?: -1L) >= watermarkFolder }
        } else {
            folderDocIds
        }
        val out = mutableMapOf<String, List<SweepFile>>()
        toWalk.forEach { (folderName, docId) ->
            val files = mutableListOf<SweepFile>()
            val listed = resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)
            if (listed == null) {
                // Review F2: a failed folder query is a fact to log, never
                // a silent "no notes" - the next tick retries the folder.
                Log.w(TAG, "SAF walk: folder query failed for $folderName (${source.name})")
                return@forEach
            }
            var undated = 0
            listed.use { c ->
                while (c.moveToNext()) {
                    // Review F4: the raw lane filters isFile; a directory
                    // whose name matches PTT-* must never become a note.
                    if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    // Review F1: a null mtime cannot be gated (the quiet
                    // window is unknowable), so the row is SKIPPED and
                    // logged, never fabricated: a made-up mtime either
                    // bypassed the mid-write gate or was dropped by the
                    // settle's re-stat. The next tick re-sees the row;
                    // live writes still arrive via CLOSE_WRITE.
                    if (c.isNull(2)) {
                        undated++
                        continue
                    }
                    files += SweepFile(
                        path = "${root.absolutePath}/$folderName/${c.getString(0)}",
                        lastModified = c.getLong(2))
                }
            }
            if (undated > 0) {
                Log.w(TAG, "SAF walk: $undated undated row(s) skipped in $folderName (${source.name})")
            }
            out[folderName] = files
        }
        SafWalk(out, folderDocIds)
    }.getOrNull()

    /** Test seam: the scan body over explicit folders (Robolectric has no
     *  WhatsApp tree; the real-path resolver is [coldCatchUp]). */
    @VisibleForTesting
    internal suspend fun coldCatchUpScan(folders: List<File>, allowRetry: Boolean = true) {
        // The raw lane: the no-grant shape, which in practice is the
        // Robolectric seam (a production arm always carries the grant).
        // The PASSED folders are
        // the listing roots (the test seam hands in temp dirs; production
        // coldCatchUp hands in root's children), so they close over here
        // instead of being re-derived from root.
        val foldersByName = folders.associateBy { it.name }
        scanCandidates(
            folderNames = folders.map { it.name },
            allowRetry = allowRetry,
            listFiles = { folderName ->
                foldersByName[folderName]?.listFiles()
                    ?.filter { it.isFile }
                    ?.map { SweepFile(it.absolutePath, it.lastModified()) }
            },
            stat = { path -> File(path).lastModified() })
    }

    /**
     * TASK-906: the sweep core, lane-agnostic. [listFiles] returns null
     * on a listing failure (the SAF lane surfaces provider errors, the
     * raw lane the FUSE denial); [stat] re-reads a single file's mtime
     * for the settle round (raw stat on the raw lane, a re-query on the
     * SAF lane; a provider that lost the row returns null and the
     * candidate is dropped rather than guessed).
     */
    private suspend fun scanCandidates(
        folderNames: List<String>,
        allowRetry: Boolean,
        listFiles: suspend (folderName: String) -> List<SweepFile>?,
        stat: suspend (path: String) -> Long?,
    ) {
        var watermark = preferencesManager.voiceNoteWatermark(source).first()
        // Review 2026-10-25: a WhatsApp counter reset (reinstall/clear-data
        // rebuilding the tree from low folder numbers) leaves a stale
        // watermark above every existing key, silently suppressing all
        // future recovery. If no scanned folder can hold a key above the
        // watermark, the key space regressed: reset and proceed unfiltered
        // (TTL and cap still bound the burst).
        val watermarkFolder = watermark?.substringBefore('/')?.toLongOrNull()
        if (watermarkFolder != null && folderNames.isNotEmpty() &&
            folderNames.none { (it.toLongOrNull() ?: -1L) >= watermarkFolder }
        ) {
            Log.w(TAG, "watermark folder $watermarkFolder is above every existing folder;" +
                " WhatsApp counter regressed, resetting the watermark")
            preferencesManager.resetVoiceNoteWatermark(source)
            watermark = null
        }
        val now = System.currentTimeMillis()
        // Review 2026-10-25: folders whose number is below the watermark's
        // folder cannot hold a newer key (the folder is the key's first
        // component), so they are pruned before listing: a steady state
        // lists only the watermark's own folder and anything newer. The
        // reset above nulls `watermark`, and the prune must follow the
        // post-reset value, not the stale pre-reset folder.
        val effectiveWatermarkFolder = watermark?.substringBefore('/')?.toLongOrNull()
        val liveFolders = if (effectiveWatermarkFolder != null) {
            folderNames.filter { (it.toLongOrNull() ?: -1L) >= effectiveWatermarkFolder }
        } else {
            folderNames
        }
        // The watermark key is the ONE ordering definition: candidates are
        // keyed before any stat (the key is pure path work), so a steady
        // arm with nothing newer never pays a lastModified syscall.
        val fresh = mutableListOf<SweepFile>()
        val candidates = liveFolders
            .flatMap { folderName ->
                listFiles(folderName).orEmpty()
                    .filter { VoiceNoteFilter.isWhatsAppVoiceNoteName(it.name) }
            }
            .mapNotNull { sweepFile ->
                val key = VoiceNoteFilter.watermarkKeyOf(File(sweepFile.path))
                if (key == null || (watermark != null && key <= watermark)) null
                else sweepFile to key
            }
            .mapNotNull { (sweepFile, key) ->
                val lastModified = sweepFile.lastModified
                when {
                    // The 831 quiet gate, same rationale: a note mid-write
                    // at arm time must not be emitted half-written (the
                    // seen dedup would swallow its live CLOSE_WRITE).
                    lastModified > now - catchUpDelayMs -> {
                        // Review finding: a note COMPLETED inside the gate
                        // window has no live event coming (its write ended
                        // before the watcher attached), so dropping it here
                        // loses it until the next arm. Collected for the
                        // single settle round below.
                        fresh += sweepFile
                        null
                    }
                    now - lastModified > coldCatchUpTtlMs -> null // stale
                    else -> Triple(sweepFile, key, lastModified)
                }
            }
            .sortedByDescending { it.second }
            // TASK-893: a decorated twin keys as its original; same key is
            // the same note, so a burst that contains both emits ONE of
            // them (the auto voice-note path dedups by full file name, so
            // without this the pair would transcribe twice).
            .distinctBy { it.second }
            .take(coldCatchUpCap)
        Log.i(TAG, "cold scan: ${candidates.size} candidate(s) after filters" +
            " (fresh=${fresh.size}, watermark=$watermark)")
        if (candidates.isNotEmpty()) {
            Log.i(TAG, "cold-start catch-up: ${candidates.size} note(s) newer than watermark $watermark")
            // TASK-889: count what actually EMITS (the seen filter can
            // swallow a live-delivered twin), not what was found.
            var emitted = 0
            candidates.forEach { (sweepFile, _, lastModified) ->
                // detectedAtMs is the note's OWN write time, not the scan
                // time: the sender matcher's notification window must not
                // pair an hours-old note with a live note's notification
                // (review 2026-10-25; the TASK-833 wrong-name invariant).
                if (onDetection(
                        entryFor(sweepFile.path)
                            .copy(detectedAtMs = lastModified, catchup = true))) {
                    emitted++
                }
            }
            if (emitted > 0) _catchUpBursts.trySend(emitted)
        }
        // ONE settle round for the quiet-gate exclusions, re-statting only
        // the collected files (no re-listing of the FUSE tree, which the
        // 831 evidence shows can stall). A write still in progress by the
        // settle delivers via its live CLOSE_WRITE (the watcher attached
        // at arm); the bound keeps a pathological folder from looping.
        if (fresh.isNotEmpty() && allowRetry) {
            val toSettle = fresh.toList()
            scope.launch(Dispatchers.IO) {
                delay(catchUpDelayMs)
                if (armed) settleFreshNotes(toSettle, stat)
            }
        }
    }

    /** The settle round: quiet + TTL + watermark re-check on exactly the
     *  once-fresh files (a live event may have advanced this source's
     *  watermark in the delay window; the re-read filter drops it then). */
    private suspend fun settleFreshNotes(
        files: List<SweepFile>,
        stat: suspend (path: String) -> Long?,
    ) {
        val watermark = preferencesManager.voiceNoteWatermark(source).first()
        val now = System.currentTimeMillis()
        val settled = files.mapNotNull { sweepFile ->
            val key = VoiceNoteFilter.watermarkKeyOf(File(sweepFile.path)) ?: return@mapNotNull null
            if (watermark != null && key <= watermark) return@mapNotNull null
            sweepFile to key
        }.filter { (sweepFile, _) ->
            // Null stat (the provider lost the row): drop, never guess an
            // mtime for a file we can no longer see.
            val lm = stat(sweepFile.path) ?: return@filter false
            lm <= now - catchUpDelayMs && now - lm <= coldCatchUpTtlMs
        }.sortedByDescending { it.second }
            .distinctBy { it.second } // same key = same note (TASK-893)
            .take(coldCatchUpCap)
        if (settled.isNotEmpty()) {
            // TASK-889: these late recoveries are part of the same
            // cold-start burst (their result posts are suppressed via the
            // catchup flag), so they count into the digest; again only
            // what actually emits (a live delivery during the settle
            // delay wins the note and the count stays honest).
            var emitted = 0
            settled.forEach { (sweepFile, _) ->
                if (onDetection(entryFor(sweepFile.path).copy(catchup = true))) emitted++
            }
            if (emitted > 0) _catchUpBursts.trySend(emitted)
        }
    }

    private companion object {
        const val TAG = "VoiceNoteDetector"

        /**
         * TASK-831: long enough that a note mid-write at folder-creation
         * time completes first (WhatsApp writes land in well under a
         * second); short enough that the catch-up still feels instant.
         */
        const val CATCH_UP_DELAY_MS = 2_000L

        /**
         * TASK-778: the cold-start catch-up window. 12h covers the
         * overnight-death case (the design's motivating scenario) while
         * excluding anything the user would call ancient history.
         */
        const val COLD_CATCH_UP_TTL_MS = 12L * 60 * 60 * 1000

        /** TASK-778: the burst cap; the digest notification is the filed follow-up. */
        const val COLD_CATCH_UP_CAP = 5
    }
}

/** The canonical voice-notes folder of [source] (the detector's watch root
 *  and the Settings picker's initial location: one owner of the path, the
 *  source descriptor). */
fun voiceNotesRoot(source: VoiceNoteSource = VoiceNoteSource.WHATSAPP): File = source.root()

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

    /**
     * TASK-778: the persisted watermark key for a note,
     * "zero-padded-folder/date/zero-padded-counter". Every component is
     * NUMERICALLY padded (folder to 20 digits, the PTT date verbatim as
     * fixed YYYYMMDD, the WA counter to 10 digits), so plain String
     * comparison equals (folder, date, counter) ordering and survives a
     * counter digit-width roll (WA9999 -> WA10000 breaks naive
     * lexicographic name order, review 2026-10-25). THE one ordering
     * definition: the cold scan sorts candidates by this key. Null for
     * non-counter parents or names that do not parse (never
     * cold-recovered; a format change deserves a conscious revisit, not a
     * silent guess). ROOT locale: no digit separators in the padding.
     *
     * TASK-893: the parse is a PREFIX match (no strict .opus tail). The
     * live filter accepts any PTT-*.opus, and decorations like WhatsApp's
     * redownload suffix ("...WA12 (1).opus") were silently unrecoverable;
     * a decorated name now keys as its ORIGINAL (same note, same
     * position), so it can never jump the watermark past the folder's
     * real notes. The catch-up scans dedupe candidates BY KEY (same key
     * is the same note: one emission per burst), which is also what keeps
     * an unseen twin from transcribing twice; the watermark itself is a
     * monotonic max, so the twin's identical key is a no-op on it.
     */
    fun watermarkKeyOf(file: File): String? {
        val folder = file.parentFile?.name?.toLongOrNull() ?: return null
        val match = PTT_NAME.find(file.name) ?: return null
        val (date, counter) = match.destructured
        val counterValue = counter.toLongOrNull() ?: return null
        return String.format(java.util.Locale.ROOT, "%020d/%s/%010d", folder, date, counterValue)
    }

    /**
     * The strict note-name PREFIX: PTT-YYYYMMDD-WA<digits> (case-
     * insensitive, per [isWhatsAppVoiceNoteName]). Anything after the
     * counter is decoration and does not change the note's identity.
     */
    private val PTT_NAME = Regex("""(?i)^PTT-(\d{8})-WA(\d+)""")

    /**
     * TASK-897: the canonical form of a note's name, "PTT-YYYYMMDD-WANNNNN.opus"
     * with the decoration stripped: a redelivered twin ("...WA1 (1).opus")
     * rebuilds to its ORIGINAL's name, so every id or dedup key derived
     * from it equals the original's. Undecorated names rebuild to
     * themselves byte-identically (counter zero-padded to 5, the WhatsApp
     * convention; a wider counter keeps its digits); names that do not
     * parse pass through unchanged (the non-PTT class is not ours to
     * canonicalize).
     */
    fun canonicalNoteName(name: String): String {
        val match = PTT_NAME.find(name) ?: return name
        val (date, counter) = match.destructured
        val counterValue = counter.toLongOrNull() ?: return name
        // %05d is a MINIMUM width: a wider counter keeps its digits.
        return String.format(java.util.Locale.ROOT, "PTT-%s-WA%05d.opus", date, counterValue)
    }
}
