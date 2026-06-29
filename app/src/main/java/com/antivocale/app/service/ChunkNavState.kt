package com.antivocale.app.service

import kotlin.jvm.Synchronized

/**
 * Sticky-cursor state machine for navigating completed transcription chunks in the
 * in-progress notification (TASK-242).
 *
 * - [liveIndex] = highest completed chunk index (-1 if none). "Following live" means
 *   the view tracks [liveIndex] as chunks complete.
 * - [viewIndex] = the chunk currently displayed: the pinned index when pinned, otherwise
 *   [liveIndex]. -1 if nothing has completed yet.
 *
 * Navigation model: tapping prev/next *pins* the view so it holds still while transcription
 * keeps completing in the background; reaching the tail (via next or [jumpToLive]) clears
 * the pin and resumes following the latest chunk. Single-chunk jobs ([isSingleChunk]) are a
 * no-op — there is nothing to navigate, so the notification keeps its plain Cancel action.
 *
 * All accessors and mutators are [Synchronized]: chunk completions arrive on the
 * orchestrator's IO coroutine while button taps arrive on the service's main thread, and
 * they share this instance.
 */
class ChunkNavState(val totalChunks: Int) {

    init {
        require(totalChunks > 0) { "totalChunks must be > 0" }
    }

    private val texts = arrayOfNulls<String>(totalChunks)
    private var liveIndexValue = -1
    private var pinnedIndexValue: Int? = null

    val isSingleChunk: Boolean get() = totalChunks < 2

    @get:Synchronized
    val liveIndex: Int get() = liveIndexValue

    @get:Synchronized
    val hasAnyChunk: Boolean get() = liveIndexValue >= 0

    /** Chunk index shown right now; -1 if nothing completed and not pinned. */
    @get:Synchronized
    val viewIndex: Int get() = pinnedIndexValue ?: liveIndexValue

    @Synchronized
    fun isPinned(): Boolean = pinnedIndexValue != null

    @Synchronized
    fun textAt(index: Int): String? =
        if (index in 0 until totalChunks) texts[index] else null

    /** Record a completed chunk's text and advance live if this is the newest so far. */
    @Synchronized
    fun onChunkCompleted(index: Int, text: String) {
        if (index !in 0 until totalChunks) return
        texts[index] = text
        if (index > liveIndexValue) liveIndexValue = index
    }

    /**
     * Move one chunk toward the oldest. Pins the view (stops following live). No-op if
     * already at the oldest reachable chunk. Returns the new [viewIndex].
     */
    @Synchronized
    fun prev(): Int {
        val current = pinnedIndexValue ?: liveIndexValue
        if (current <= 0) return viewIndex
        pinnedIndexValue = current - 1
        return viewIndex
    }

    /**
     * Move one chunk toward the newest. If this reaches the live tail, clears the pin so
     * the view resumes following live. Returns the new [viewIndex].
     */
    @Synchronized
    fun next(): Int {
        val current = pinnedIndexValue ?: liveIndexValue
        if (current >= liveIndexValue) {
            pinnedIndexValue = null
            return viewIndex
        }
        val target = current + 1
        pinnedIndexValue = if (target >= liveIndexValue) null else target
        return viewIndex
    }

    /** Stop pinning and follow the latest completed chunk again. */
    @Synchronized
    fun jumpToLive(): Int {
        pinnedIndexValue = null
        return viewIndex
    }

    /** Atomic consistent snapshot for rendering, so the action set, displayed text, and progress
     *  bar can't disagree when chunk completions and button taps race on different threads. */
    data class Snapshot(
        val totalChunks: Int,
        val liveIndex: Int,
        val viewIndex: Int,
        val pinned: Boolean,
        val text: String?
    )

    @Synchronized
    fun snapshot(): Snapshot {
        val view = pinnedIndexValue ?: liveIndexValue
        return Snapshot(
            totalChunks = totalChunks,
            liveIndex = liveIndexValue,
            viewIndex = view,
            pinned = pinnedIndexValue != null,
            text = if (view in 0 until totalChunks) texts[view] else null
        )
    }
}
