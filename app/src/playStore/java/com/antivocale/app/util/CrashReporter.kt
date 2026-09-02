package com.antivocale.app.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * Central point for reporting exceptions to Firebase Crashlytics.
 *
 * Provides both a [CoroutineExceptionHandler] for coroutine scopes and a
 * standalone [report] method for thread-level uncaught exceptions.
 *
 * Usage with CoroutineScope:
 *   val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
 *
 * Usage from UncaughtExceptionHandler:
 *   CrashReporter.report(throwable, "Uncaught on ${thread.name}")
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** TASK-396: set by BridgeApplication at startup; must be set before report() can mark OOM. */
    @Volatile var filesDir: java.io.File? = null
    private const val KEY_CONTEXT = "crash_context"
    private const val KEY_MEMORY_CLASS_MB = "memory_class_mb"
    private const val KEY_TOTAL_RAM_BYTES = "total_ram_bytes"
    private const val KEY_IS_LOW_RAM_DEVICE = "is_low_ram_device"
    private const val OOM_MARKER_PATH = "/data/data/com.antivocale.app/files/last_crash_oom"

    val handler = CoroutineExceptionHandler { context, throwable ->
        val name = context[CoroutineName]?.name ?: "unnamed"
        report(throwable, "Uncaught exception in coroutine [$name]")
    }

    fun report(throwable: Throwable, context: String) {
        Log.e(TAG, context, throwable)
        markOomIfOOM(throwable)
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey(KEY_CONTEXT, context)
                recordException(throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to Crashlytics", e)
        }
    }

    /** TASK-430: persistent keys annotating every subsequent report with the
     *  device's memory profile (no public per-model heap-limit dataset exists,
     *  so OOM reports must carry their own). Null readings are skipped; they
     *  occur on unit-test Contexts whose ActivityManager is unavailable. */
    fun setMemoryInfo(memoryClassMb: Int?, totalRamBytes: Long?, isLowRamDevice: Boolean?) {
        try {
            FirebaseCrashlytics.getInstance().apply {
                memoryClassMb?.let { setCustomKey(KEY_MEMORY_CLASS_MB, it) }
                totalRamBytes?.let { setCustomKey(KEY_TOTAL_RAM_BYTES, it) }
                isLowRamDevice?.let { setCustomKey(KEY_IS_LOW_RAM_DEVICE, it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set memory info on Crashlytics", e)
        }
    }

    /** TASK-396 pt.2: persist an OOM marker readable at the next cold start.
     *  Resolves via the injected [filesDir] (debug builds run as .debug and the
     *  hardcoded production path is unwritable there); falls back to it otherwise. */
    private fun markerFile(): java.io.File =
        filesDir?.resolve("last_crash_oom") ?: java.io.File(OOM_MARKER_PATH)

    private fun markOomIfOOM(throwable: Throwable) {
        if (throwable is OutOfMemoryError) {
            runCatching {
                markerFile().writeText("1")
            }
        }
    }

    /** True when the previous process died on an OutOfMemoryError. Clears the marker. */
    fun consumeLastCrashWasOOM(): Boolean {
        val marker = markerFile()
        return if (marker.exists()) {
            marker.delete()
            true
        } else false
    }
}
