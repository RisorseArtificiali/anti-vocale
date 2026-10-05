package com.antivocale.app.data

import com.antivocale.app.util.SharedAudioHandler

/**
 * TASK-741 (GH #125) slice 1: the pure decision core of the scheduled
 * folder watch. Given one scan's listing of a user-picked recorder folder
 * and the snapshot the previous scans built, it decides which files to
 * enqueue, which to hold for a stability check, and what the next snapshot
 * is. No Android types: the worker maps DocumentFile children to [Child]
 * and feeds this, so the whole policy is JVM-unit-testable.
 *
 * POLICY (the contract the worker and the tests pin):
 *  - FIRST SIGHT of a file is never enqueued: a recorder still writing the
 *    file would be transcribed truncated. The file enters the snapshot as
 *    seen-but-unenqueued and becomes eligible once a LATER scan sees the
 *    same name with the same size (the recorder finished). Stability is
 *    SIZE-ONLY; [Child.lastModified] is carried for enqueue-time logging
 *    and routing, never for a policy decision.
 *  - A size change between sightings resets the wait (the file was still
 *    growing); the new size becomes the wait baseline.
 *  - Enqueued files are deduped by NAME within the retention window: a
 *    renamed file re-enters the stability wait and re-transcribes (the
 *    task's documented rename risk; content-hashing every file would cost
 *    more than the occasional duplicate transcription). Vanished files
 *    drop their unenqueued entries but KEEP their enqueued entries (a
 *    moved-out-and-back file must not re-transcribe), subject to the
 *    retention cap.
 *  - Files that vanished from the listing drop out of the snapshot (their
 *    enqueued facts survive per the rule above).
 *  - At most [batchCap] files enqueue per run (huge folders drain over
 *    successive runs); the deferred rest stay eligible for the next scan.
 *
 * WORKER DUTIES the planner cannot enforce (slice 3 owns them; named here
 * so they are contract, not folklore):
 *  - An EMPTY OR FAILED listing is scan-abandoned: keep the previous
 *    snapshot verbatim and retry later. Feeding a provider error in as an
 *    empty list would wipe the awaiting entries and force every file back
 *    through the stability wait (or worse, mass re-enqueue after retention
 *    churn). Only a listing the worker actually read may become a Plan.
 *  - [Plan.nextSnapshot] stamps every enqueued file with this run's [now].
 *    A file whose InferenceEnqueue outcome was [com.antivocale.app.service.InferenceEnqueue.Outcome.Failed]
 *    must have its new snapshot entry DEMOTED to enqueuedAt = null before
 *    persisting: the entry stays (so the next scan reads it as a stable
 *    second sight, not a first sight) and retries in ONE scan. A fallback
 *    notification counts as enqueued: TASK-500's trampoline preserves the
 *    full request.
 */
object FolderScanPlanner {

    /** One scanned child; name is unique within a folder listing. */
    data class Child(val name: String, val size: Long, val lastModified: Long)

    data class Plan(
        /** Stable, unenqueued files the worker should enqueue this run. */
        val toEnqueue: List<Child>,
        /** First-sight or grown files now recorded for the stability check. */
        val awaitingStability: List<Child>,
        /** The full replacement snapshot for this folder. */
        val nextSnapshot: List<FileSnapshot>,
        /** True when [toEnqueue] hit the cap and eligible files remain. */
        val morePending: Boolean,
    )

    const val DEFAULT_BATCH_CAP = 20
    const val DEFAULT_PROCESSED_RETENTION = 1000

    /** Audio-only containers; the video containers come from the share
     *  flow's set (the audio track is extracted at decode time), so the
     *  two surfaces cannot drift on what counts as input. */
    // Deliberately excludes wma/aiff/aif: the decode path is
    // MediaExtractor-only, and it has no ASF or AIFF demuxer - an
    // autonomous scan must not enqueue files that are guaranteed to fail.
    private val AUDIO_ONLY_EXTENSIONS = setOf(
        "m4a", "m4b", "aac", "mp3", "wav", "opus", "ogg", "oga", "amr", "flac",
    )

    val AUDIO_EXTENSIONS = AUDIO_ONLY_EXTENSIONS + SharedAudioHandler.VIDEO_EXTENSIONS

    fun isAudioName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun plan(
        children: List<Child>,
        previous: List<FileSnapshot>,
        now: Long,
        batchCap: Int = DEFAULT_BATCH_CAP,
        processedRetention: Int = DEFAULT_PROCESSED_RETENTION,
    ): Plan {
        require(batchCap > 0) { "batchCap must drain (was $batchCap)" }
        require(processedRetention > 0) { "processedRetention must keep facts (was $processedRetention)" }
        val prevByName = previous.associateBy { it.name }
        val eligible = mutableListOf<Child>()
        val awaiting = mutableListOf<Child>()
        val enqueuedKept = mutableListOf<FileSnapshot>()

        for (child in children.filter { isAudioName(it.name) }) {
            val prev = prevByName[child.name]
            when {
                // Already handed over: keep the dedup fact, never re-enqueue.
                prev?.enqueuedAt != null -> enqueuedKept += prev
                // Second sight, size unchanged since the previous scan:
                // the writer is done, the file is eligible.
                prev != null && prev.size == child.size -> eligible += child
                // First sight, or the size moved since the last scan.
                else -> awaiting += child
            }
        }

        // A snapshot entry carries exactly what the policy re-reads next
        // scan: the identity (name) and the stability baseline (size).
        fun Child.snap(enqueuedAt: Long?) = FileSnapshot(name, size, enqueuedAt)

        val capped = eligible.take(batchCap)
        // Cap-deferred and first-sight files share the seen-unenqueued
        // shape; the deferred ones re-enter the eligible pool next scan
        // instead of restarting the stability wait.
        val unprocessed = awaiting + eligible.drop(batchCap)
        // Enqueued facts of files no longer in the listing survive here:
        // the moved-out-and-back rule. The retention cap bounds ONLY these
        // vanished facts (present files' facts are bounded by the folder
        // itself): capping present facts would re-transcribe the tail of a
        // big folder forever, scan after scan (741 review F1).
        val vanishedFacts = previous.filter { it.enqueuedAt != null && it.name !in childNames(children) }
            .sortedBy { it.enqueuedAt!! }
        val keptVanished = vanishedFacts.takeLast((processedRetention - enqueuedKept.size).coerceAtLeast(0))
        val kept = enqueuedKept + keptVanished

        return Plan(
            toEnqueue = capped,
            awaitingStability = awaiting,
            nextSnapshot = kept + capped.map { it.snap(now) } + unprocessed.map { it.snap(null) },
            morePending = eligible.size > capped.size,
        )
    }

    private fun childNames(children: List<Child>) = children.mapTo(mutableSetOf()) { it.name }
}

/**
 * The per-folder dedup snapshot (one JSON list per watched folder; see the
 * store in slice 2). Only the fields the policy re-reads survive here;
 * enqueue-time logging reads [FolderScanPlanner.Child] instead.
 */
data class FileSnapshot(
    val name: String,
    val size: Long,
    val enqueuedAt: Long?,
)
