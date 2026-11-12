package com.antivocale.app.audio

import android.app.ActivityManager
import android.content.Context

/** One owner for the memory readings [AudioDurationPolicy] consumes. */
internal object MemoryReadings {
    // String overload + safe cast: unit-test Context fakes return generic
    // objects from getSystemService, and the class-based overload's
    // implicit checkcast crashes them.
    private fun activityManager(context: Context): ActivityManager? =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val info = ActivityManager.MemoryInfo()
        val am = activityManager(context) ?: return null
        am.getMemoryInfo(info)
        return info
    }

    /** ActivityManager.MemoryInfo.availMem, or null when the service is unavailable. */
    fun availableRamBytes(context: Context): Long? = memoryInfo(context)?.availMem

    /**
     * availMem and totalMem from ONE getMemoryInfo call (the struct serves
     * both; two reads would pay the binder IPC twice per request,
     * TASK-909 review F3). Null when the service is unavailable.
     */
    fun availableAndTotalRamBytes(context: Context): Pair<Long, Long>? =
        memoryInfo(context)?.let { it.availMem to it.totalMem }

    /** ActivityManager.MemoryInfo.totalMem, or null when the service is unavailable. */
    fun totalRamBytes(context: Context): Long? = memoryInfo(context)?.totalMem

    /**
     * TASK-996: totalMem cached for the PROCESS lifetime. totalMem is a
     * device constant, and the Models tab's tier line plus every fit chip
     * each used to pay their own getMemoryInfo binder round trip (the
     * N+1 the review caught); one read per process serves them all. The
     * cache stores the NULL failure too (fail-open stays fail-open), and
     * unit tests that need fresh reads keep using [totalRamBytes].
     */
    @Volatile
    private var cachedTotalRamBytes: Long? = null
    @Volatile
    private var totalRamCached = false

    fun totalRamBytesCached(context: Context): Long? {
        if (!totalRamCached) {
            cachedTotalRamBytes = totalRamBytes(context)
            totalRamCached = true
        }
        return cachedTotalRamBytes
    }

    /** ActivityManager.getMemoryClass in MB (no largeHeap in the manifest), or null when the service is unavailable. */
    fun memoryClassMb(context: Context): Int? = activityManager(context)?.memoryClass

    /** ActivityManager.isLowRamDevice, or null when the service is unavailable. */
    fun isLowRamDevice(context: Context): Boolean? = activityManager(context)?.isLowRamDevice

    /** Dalvik heap limit for this process (no largeHeap in the manifest). */
    fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()

    /** Logical cores (Runtime). TASK-909: one input of the device tier. */
    fun coreCount(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * Build.VERSION.MEDIA_PERFORMANCE_CLASS, 0 below API 31 or when the
     * device declares none. TASK-909: one input of the device tier.
     */
    fun mediaPerformanceClass(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.VERSION.MEDIA_PERFORMANCE_CLASS
        } else {
            0
        }
}
