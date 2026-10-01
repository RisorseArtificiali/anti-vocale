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
import org.junit.Assert.assertNull

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
        val native = android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
        val anr = android.app.ApplicationExitInfo.REASON_ANR
        val other = android.app.ApplicationExitInfo.REASON_OTHER
        val records = listOf(
            NativeCrashDetector.ExitRecord(lowMem, 100L, "lmkd"),
            NativeCrashDetector.ExitRecord(anr, 200L, "user saw a dialog: not silent"),
            NativeCrashDetector.ExitRecord(signaled, 300L, "PowerKeeper"),
            NativeCrashDetector.ExitRecord(native, 400L, "sherpa model load abort"),
            NativeCrashDetector.ExitRecord(lowMem, 50L, "already reported"),
            // TASK-426: the Android 17 memory-cap kill rides REASON_OTHER and
            // is separable only by the MemoryLimiter literal; a plain
            // REASON_OTHER (user stop) stays unreported.
            NativeCrashDetector.ExitRecord(other, 500L, "MemoryLimiter:AnonSwap"),
            NativeCrashDetector.ExitRecord(other, 600L, "user requested"),
        )
        val result = NativeCrashDetector.unreported(records, lastReportedTs = 50L)
        assertEquals(
            listOf(100L, 300L, 400L, 500L),
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

    /**
     * TASK-684: the sibling raw read the freezer classifier consumes. It must
     * touch no prefs (the banner and telemetry marks stay untouched) and
     * degrade to null with no history.
     */
    @Test
    fun `mostRecentExit is null with no exit history and consumes no dedup marks`() {
        assertNull(NativeCrashDetector.mostRecentExit(context))
        val prefs = context.getSharedPreferences("native_crash_detection", android.content.Context.MODE_PRIVATE)
        assertEquals("raw read advanced no dedup mark", -1L, prefs.getLong("last_native_crash_ts", -1L))
        assertEquals("raw read advanced no report mark", -1L, prefs.getLong("last_reported_death_ts", -1L))
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
