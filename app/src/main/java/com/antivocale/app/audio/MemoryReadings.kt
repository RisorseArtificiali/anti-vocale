package com.antivocale.app.audio

import android.app.ActivityManager
import android.content.Context

/** One owner for the memory readings [AudioDurationPolicy] consumes. */
internal object MemoryReadings {
    /** ActivityManager.MemoryInfo.availMem, or null when the service is unavailable. */
    fun availableRamBytes(context: Context): Long? {
        val info = ActivityManager.MemoryInfo()
        // String overload + safe cast: unit-test Context fakes return generic
        // objects from getSystemService, and the class-based overload's
        // implicit checkcast crashes them.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** Dalvik heap limit for this process (no largeHeap in the manifest). */
    fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()
}
