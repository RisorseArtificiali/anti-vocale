package com.antivocale.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [ChunkNavState] — the sticky-cursor state machine that backs
 * chunk prev/next navigation in the in-progress transcription notification.
 *
 * No Android dependencies; plain JUnit. See TASK-242.
 */
class ChunkNavStateTest {

    @Test
    fun `single chunk is flagged and nav is a no-op`() {
        val s = ChunkNavState(1)
        assertTrue(s.isSingleChunk)
        s.onChunkCompleted(0, "hi")
        assertEquals(0, s.viewIndex)
        assertEquals(0, s.prev())
        assertEquals(0, s.next())
        assertFalse(s.isPinned())
    }

    @Test
    fun `live index tracks the highest completed chunk even when completed out of order`() {
        val s = ChunkNavState(4)
        s.onChunkCompleted(2, "c2")
        s.onChunkCompleted(0, "c0")
        assertEquals(2, s.liveIndex)
        assertEquals(2, s.viewIndex) // following live
        assertEquals("c2", s.textAt(2))
    }

    @Test
    fun `prev pins the view one behind live`() {
        val s = ChunkNavState(4)
        s.onChunkCompleted(0, "c0")
        s.onChunkCompleted(1, "c1")
        s.onChunkCompleted(2, "c2")
        // following live at 2
        val v = s.prev()
        assertEquals(1, v)
        assertTrue(s.isPinned())
        assertEquals(1, s.viewIndex)
    }

    @Test
    fun `prev at the oldest chunk is a no-op and stays pinned`() {
        val s = ChunkNavState(3)
        s.onChunkCompleted(0, "c0")
        s.onChunkCompleted(1, "c1")
        s.prev() // pin to 0
        assertEquals(0, s.viewIndex)
        assertEquals(0, s.prev()) // already at oldest
        assertTrue(s.isPinned())
    }

    @Test
    fun `next advances toward the tail and unpins when it reaches live`() {
        val s = ChunkNavState(4)
        s.onChunkCompleted(0, "c0")
        s.onChunkCompleted(1, "c1")
        s.onChunkCompleted(3, "c3")
        s.prev() // following live(3) -> pin 2
        assertEquals(2, s.viewIndex)
        assertEquals(3, s.next()) // next reaches tail -> follow live again
        assertFalse(s.isPinned())
        assertEquals(3, s.viewIndex)
    }

    @Test
    fun `jump to live clears the pin`() {
        val s = ChunkNavState(3)
        s.onChunkCompleted(0, "c0")
        s.onChunkCompleted(2, "c2")
        s.prev() // pin 1
        assertTrue(s.isPinned())
        s.jumpToLive()
        assertFalse(s.isPinned())
        assertEquals(2, s.viewIndex)
    }

    @Test
    fun `sticky - a newly completed chunk does not move a pinned view`() {
        val s = ChunkNavState(5)
        s.onChunkCompleted(0, "c0")
        s.onChunkCompleted(1, "c1")
        s.onChunkCompleted(2, "c2")
        s.prev() // pin at 1
        assertEquals(1, s.viewIndex)
        s.onChunkCompleted(3, "c3") // live advances to 3
        assertEquals(3, s.liveIndex)
        assertEquals(1, s.viewIndex) // still pinned (sticky)
        assertTrue(s.isPinned())
    }

    @Test
    fun `textAt returns stored text and null for unknown or not-yet-completed`() {
        val s = ChunkNavState(3)
        s.onChunkCompleted(1, "mid")
        assertEquals("mid", s.textAt(1))
        assertNull(s.textAt(0))
        assertNull(s.textAt(5))
    }
}
