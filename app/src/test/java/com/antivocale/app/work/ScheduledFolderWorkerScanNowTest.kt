package com.antivocale.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK-803: the Scan-now one-shot must stay a plain request. Expedited
 * routing is the API 26-30 failure: WorkManager's foreground fallback calls
 * getForegroundInfo() there, the CoroutineWorker default throws, and the
 * tapped scan resolves FAILED without a trace. Dispatch-path decisions land
 * pinned (PR #28 precedent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledFolderWorkerScanNowTest {

    @Test
    fun `the scan-now request is not expedited`() {
        val request = ScheduledFolderScheduler.scanNowRequest("content://tree/primary%3ADownload")
        assertFalse(request.workSpec.expedited)
    }

    @Test
    fun `the scan-now request carries the folder and the manual flag`() {
        val uri = "content://tree/primary%3ADownload"
        val request = ScheduledFolderScheduler.scanNowRequest(uri)
        assertEquals(uri, request.workSpec.input.getString(ScheduledFolderWorker.KEY_TREE_URI))
        assertEquals(true, request.workSpec.input.getBoolean(ScheduledFolderWorker.KEY_SCAN_NOW, false))
        assertTrue(request.workSpec.isPeriodic.not())
    }
}
