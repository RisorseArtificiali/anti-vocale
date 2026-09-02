package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-432 storage gate: the margin decision is pure and JVM-tested; the two
 * plumbing lines (AssetFileDescriptor size read, StatFs query) are inspection-
 * verified per the spec's fallback (the device cannot be practically filled).
 */
class SharedAudioHandlerStorageTest {
    @Test fun `admits when free space covers source plus margin`() {
        assertTrue(SharedAudioHandler.hasFreeSpace(
            availableBytes = 500L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
    }
    @Test fun `rejects when free space misses the margin`() {
        // exactly source size, no margin: must refuse
        assertFalse(SharedAudioHandler.hasFreeSpace(
            availableBytes = 100L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
        // source + 10% but not the flat 32MB floor
        assertFalse(SharedAudioHandler.hasFreeSpace(
            availableBytes = 115L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
    }
    @Test fun `neededMb reports source plus both margins`() {
        assertEquals(100 + 10 + 32, SharedAudioHandler.neededMb(100L * 1024 * 1024))
    }
}
