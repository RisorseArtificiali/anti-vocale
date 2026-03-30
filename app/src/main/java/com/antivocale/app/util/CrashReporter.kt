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
    private const val KEY_CONTEXT = "crash_context"

    val handler = CoroutineExceptionHandler { context, throwable ->
        val name = context[CoroutineName]?.name ?: "unnamed"
        report(throwable, "Uncaught exception in coroutine [$name]")
    }

    fun report(throwable: Throwable, context: String) {
        Log.e(TAG, context, throwable)
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey(KEY_CONTEXT, context)
                recordException(throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to Crashlytics", e)
        }
    }
}
