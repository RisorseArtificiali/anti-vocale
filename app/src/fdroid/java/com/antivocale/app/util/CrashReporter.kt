package com.antivocale.app.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * No-op CrashReporter for the fdroid (Firebase-free) build.
 *
 * Preserves the same API as the playStore implementation so callers
 * (BridgeApplication, services, workers) remain unchanged. Exceptions
 * are logged to logcat only; nothing is reported off-device.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    val handler = CoroutineExceptionHandler { context, throwable ->
        val name = context[CoroutineName]?.name ?: "unnamed"
        report(throwable, "Uncaught exception in coroutine [$name]")
    }

    fun report(throwable: Throwable, context: String) {
        Log.e(TAG, context, throwable)
    }
}
