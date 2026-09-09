package com.antivocale.app.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.antivocale.app.util.NativeCrashDetector.CrashCheckResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NativeCrashDetectorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `check returns None when no exit history`() {
        // Fresh app process has no historical exit reasons.
        val result = NativeCrashDetector.checkForRecentCrash(context)
        assertTrue("expected None", result is CrashCheckResult.None)
    }

    @Test
    fun `unreported keeps only silent-death reasons past the mark, oldest first`() {
        val lowMem = android.app.ApplicationExitInfo.REASON_LOW_MEMORY
        val signaled = android.app.ApplicationExitInfo.REASON_SIGNALED
        val anr = android.app.ApplicationExitInfo.REASON_ANR
        val records = listOf(
            NativeCrashDetector.ExitRecord(lowMem, 100L, "lmkd"),
            NativeCrashDetector.ExitRecord(anr, 200L, "user saw a dialog: not silent"),
            NativeCrashDetector.ExitRecord(signaled, 300L, "PowerKeeper"),
            NativeCrashDetector.ExitRecord(lowMem, 50L, "already reported"),
        )
        val result = NativeCrashDetector.unreported(records, lastReportedTs = 50L)
        assertEquals(
            listOf(100L, 300L),
            result.map { it.timestamp },
        )
    }

    @Test
    fun `reportUnreportedDeaths is a no-op with no exit history`() {
        NativeCrashDetector.reportUnreportedDeaths(context)
        val mark = context.getSharedPreferences("native_crash_detection", android.content.Context.MODE_PRIVATE)
            .getLong("last_reported_death_ts", -1L)
        assertEquals("no deaths to report means no mark advanced", -1L, mark)
    }

    @Test
    fun `LowMemory and NativeCrash carry their timestamp and are distinct result types`() {
        val lowMem = CrashCheckResult.LowMemory(timestamp = 1234L)
        val nativeCrash = CrashCheckResult.NativeCrash(timestamp = 5678L)
        assertEquals(1234L, lowMem.timestamp)
        assertEquals(5678L, nativeCrash.timestamp)
        assertTrue("the two variants are not the same type", lowMem::class != nativeCrash::class)
    }
}
