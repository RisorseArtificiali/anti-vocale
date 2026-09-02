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

    /** ActivityManager.MemoryInfo.totalMem, or null when the service is unavailable. */
    fun totalRamBytes(context: Context): Long? = memoryInfo(context)?.totalMem

    /** ActivityManager.getMemoryClass in MB (no largeHeap in the manifest), or null when the service is unavailable. */
    fun memoryClassMb(context: Context): Int? = activityManager(context)?.memoryClass

    /** ActivityManager.isLowRamDevice, or null when the service is unavailable. */
    fun isLowRamDevice(context: Context): Boolean? = activityManager(context)?.isLowRamDevice

    /** Dalvik heap limit for this process (no largeHeap in the manifest). */
    fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()
}
