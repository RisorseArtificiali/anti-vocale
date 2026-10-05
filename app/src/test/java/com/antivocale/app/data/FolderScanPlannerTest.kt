package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-741 slice 1: the folder-watch decision policy. Every rule the worker
 * will rely on is pinned here: stability wait, size-change reset, processed
 * dedup, vanished-file cleanup, batch cap, retention eviction, and the
 * extension filter.
 */
class FolderScanPlannerTest {

    private fun child(name: String, size: Long = 100, mtime: Long = 1) =
        FolderScanPlanner.Child(name, size, mtime)

    private fun plan(
        children: List<FolderScanPlanner.Child>,
        previous: List<FileSnapshot> = emptyList(),
        now: Long = 1000,
        batchCap: Int = FolderScanPlanner.DEFAULT_BATCH_CAP,
        processedRetention: Int = FolderScanPlanner.DEFAULT_PROCESSED_RETENTION,
    ) = FolderScanPlanner.plan(children, previous, now, batchCap, processedRetention)

    @Test
    fun `first sight waits for stability instead of enqueuing`() {
        val p = plan(listOf(child("rec.m4a")))
        assertEquals(emptyList<FolderScanPlanner.Child>(), p.toEnqueue)
        assertEquals(listOf("rec.m4a"), p.awaitingStability.map { it.name })
        // the snapshot records the seen-but-unprocessed fact
        assertEquals(1, p.nextSnapshot.size)
        assertEquals(null, p.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `second sight with unchanged size enqueues`() {
        val first = plan(listOf(child("rec.m4a", size = 42)))
        val second = plan(listOf(child("rec.m4a", size = 42)), first.nextSnapshot, now = 2000)
        assertEquals(listOf("rec.m4a"), second.toEnqueue.map { it.name })
        assertEquals(2000L, second.nextSnapshot.single().enqueuedAt)
        assertFalse(second.morePending)
    }

    @Test
    fun `a size change between sightings resets the wait`() {
        val first = plan(listOf(child("rec.m4a", size = 42)))
        val grown = plan(listOf(child("rec.m4a", size = 60)), first.nextSnapshot, now = 2000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), grown.toEnqueue)
        assertEquals(60L, grown.nextSnapshot.single().size)
        // stability against the NEW baseline enqueues on the third scan
        val stable = plan(listOf(child("rec.m4a", size = 60)), grown.nextSnapshot, now = 3000)
        assertEquals(listOf("rec.m4a"), stable.toEnqueue.map { it.name })
    }

    @Test
    fun `processed files are never re-enqueued`() {
        val first = plan(listOf(child("rec.m4a")))
        val second = plan(listOf(child("rec.m4a")), first.nextSnapshot, now = 2000)
        val third = plan(listOf(child("rec.m4a")), second.nextSnapshot, now = 3000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), third.toEnqueue)
        assertEquals(emptyList<FolderScanPlanner.Child>(), third.awaitingStability)
        // the dedup fact survives (stamped by the scan that enqueued it)
        assertEquals(2000L, third.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `an enqueued file that later grows stays deduped`() {
        val first = plan(listOf(child("rec.m4a")))
        val second = plan(listOf(child("rec.m4a")), first.nextSnapshot, now = 2000)
        // the file is REWRITTEN in place (size moves) after it was already
        // enqueued once: the dedup fact wins over the stability branch
        val rewritten = plan(listOf(child("rec.m4a", size = 999)), second.nextSnapshot, now = 3000)
        assertEquals(emptyList<FolderScanPlanner.Child>(), rewritten.toEnqueue)
        assertEquals(emptyList<FolderScanPlanner.Child>(), rewritten.awaitingStability)
        assertEquals(2000L, rewritten.nextSnapshot.single().enqueuedAt)
    }

    @Test
    fun `retention never evicts the facts of still-present files`() {
        val children = (1..5).map { child("rec$it.m4a") }
        val first = plan(children)
        val second = plan(children, first.nextSnapshot, now = 2000)
        // all five enqueued; a retention of 2 must NOT evict present files
        val kept = plan(children, second.nextSnapshot, now = 3000, batchCap = 5, processedRetention = 2)
        assertEquals(5, kept.nextSnapshot.size)
        // the cap does bound VANISHED facts (simulate by removing files)
        val shrunk = children.drop(2)
        val third = plan(shrunk, second.nextSnapshot, now = 3000, batchCap = 5, processedRetention = 1)
        assertEquals(3, third.nextSnapshot.size) // 2 present + 1 newest vanished
    }

    @Test
    fun `vanished files drop their unenqueued entries but keep enqueued facts`() {
        val first = plan(listOf(child("a.m4a"), child("b.m4a")))
        val second = plan(listOf(child("a.m4a"), child("b.m4a")), first.nextSnapshot, now = 2000)
        // both stable and enqueued on scan 2
        val third = plan(listOf(child("a.m4a")), second.nextSnapshot, now = 3000)
        // b.m4a vanished AFTER being enqueued: its dedup fact survives
        // (moved-out-and-back must not re-transcribe)...
        assertEquals(setOf("a.m4a", "b.m4a"), third.nextSnapshot.map { it.name }.toSet())
        assertEquals(2000L, third.nextSnapshot.first { it.name == "b.m4a" }.enqueuedAt)
        // ...while an UNENQUEUED vanish (never stable) is forgotten
        val gone = plan(listOf(child("c.m4a")))
        val after = plan(emptyList(), gone.nextSnapshot, now = 2000)
        assertEquals(0, after.nextSnapshot.size)
    }

    @Test
    fun `non-audio files are ignored entirely`() {
        val p = plan(listOf(child("cover.jpg"), child("notes.txt"), child("rec.opus")))
        assertEquals(listOf("rec.opus"), p.awaitingStability.map { it.name })
        assertEquals(listOf("rec.opus"), p.nextSnapshot.map { it.name })
    }

    @Test
    fun `the extension filter is case-insensitive and covers video containers`() {
        assertTrue(FolderScanPlanner.isAudioName("REC.M4A"))
        assertTrue(FolderScanPlanner.isAudioName("clip.MP4"))
        assertFalse(FolderScanPlanner.isAudioName("cover.png"))
        assertFalse(FolderScanPlanner.isAudioName("noext"))
    }

    @Test
    fun `the batch cap defers the tail to the next run`() {
        val children = (1..25).map { child("rec%02d.m4a".format(it)) }
        val first = plan(children)
        val second = plan(children, first.nextSnapshot, now = 2000, batchCap = 10)
        assertEquals(10, second.toEnqueue.size)
        assertTrue(second.morePending)
        // the deferred 15 stay unprocessed and eligible for scan 3
        val third = plan(children, second.nextSnapshot, now = 3000, batchCap = 10)
        assertEquals(10, third.toEnqueue.size)
        assertTrue(third.morePending)
        val fourth = plan(children, third.nextSnapshot, now = 4000, batchCap = 10)
        assertEquals(5, fourth.toEnqueue.size)
        assertFalse(fourth.morePending)
    }

}
