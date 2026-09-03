package com.antivocale.app.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.BuildConfig
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.testing.TestSpiOps
import com.antivocale.app.util.CrashReporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Test SPI for debug builds (TASK-409): a direct broadcast seam to read and
 * write the app's testable state, replacing the dozens of adb UI-driving
 * calls a device-test session otherwise burns on preference and model
 * inspection. Not a product feature.
 *
 * Registered ONLY by the debug manifest overlay (src/debug/AndroidManifest.xml),
 * so release builds of both flavors contain neither this receiver nor its
 * TEST_SPI intent filter; [BuildConfig.DEBUG] is a second, runtime gate. The
 * op handling lives in [TestSpiOps] (main source set) so the shared unit-test
 * suite compiles against it for every variant.
 *
 * Usage via adb (full op table in docs/testing-spi.md):
 *   adb shell am broadcast -a com.antivocale.app.TEST_SPI --es op get
 *
 * Responses go out on BOTH channels as one line of JSON:
 *  - resultCode RESULT_OK + setResultData (an `am broadcast` completion may
 *    surface it),
 *  - Log.i("TestSpi", json) as the always-works channel
 *    (adb shell logcat -s TestSpi:I).
 *
 * Transcription is deliberately NOT one of the ops: it already has a
 * production broadcast (TaskerRequestReceiver, com.antivocale.app.PROCESS_REQUEST).
 */
@AndroidEntryPoint
class TestSpiReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var externalModelStore: ExternalModelStore

    companion object {
        const val TAG = "TestSpi"
        const val ACTION_TEST_SPI = "com.antivocale.app.TEST_SPI"

        // String extras, mirroring the Tasker receiver's extras style.
        const val EXTRA_OP = "op"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"

        /** Catalog entry id; required by op=set key=sherpa_path. */
        const val EXTRA_ENTRY = "entry"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_SPI) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }
        // Belt-and-braces on top of the debug-only manifest registration.
        if (!BuildConfig.DEBUG) return

        val pendingResult = goAsync()
        val ops = TestSpiOps(preferencesManager, externalModelStore)
        val op = intent.getStringExtra(EXTRA_OP)
        val key = intent.getStringExtra(EXTRA_KEY)
        val value = intent.getStringExtra(EXTRA_VALUE)
        val entry = intent.getStringExtra(EXTRA_ENTRY)

        // ModelPreloadReceiver idiom: goAsync plus a scope per receive,
        // finish() in finally. A local scope (not the @ApplicationScope one,
        // whose contract forbids cancelling) so the receive is fully done
        // once finish() returns.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
        scope.launch {
            try {
                // handle() answers every request with JSON, errors included.
                val json = ops.handle(op, key, value, entry)
                // PendingResult setters, the goAsync-sanctioned API for this
                // async window: onReceive has already returned and we are on IO.
                pendingResult.resultCode = Activity.RESULT_OK
                pendingResult.setResultData(json)
                Log.i(TAG, json)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
