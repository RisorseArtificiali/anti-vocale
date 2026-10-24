package com.antivocale.app.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antivocale.app.ui.AppNavigation
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
    @Inject lateinit var importer: com.antivocale.app.data.ExternalModelImportOperations
    @Inject lateinit var voiceNoteIdentityCache: com.antivocale.app.receiver.VoiceNoteIdentityCache
    @Inject lateinit var backendManager: com.antivocale.app.transcription.TranscriptionBackendManager
    // TASK-826: the armed flag behind op=voice_note_state.
    @Inject lateinit var voiceNoteDetectionCoordinators: com.antivocale.app.data.VoiceNoteDetectionCoordinators

    companion object {
        const val TAG = "TestSpi"
        const val ACTION_TEST_SPI = "com.antivocale.app.TEST_SPI"

        // String extras, mirroring the Tasker receiver's extras style.
        const val EXTRA_OP = "op"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"

        /** Catalog entry id; required by op=set key=sherpa_path. */
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_URL = "url"
        const val EXTRA_FAMILY = "family"
        const val EXTRA_MODEL_TYPE = "model_type"

        /** TASK-486: navigation destination; required by op=nav. */
        const val EXTRA_DEST = "dest"

        /** TASK-815: optional scroll spec for op=nav (top|bottom|+N/-N). */
        const val EXTRA_SCROLL = "scroll"

        /** TASK-826: op=voice_note_grant_open (no extras; the activity
         *  opens the same folder picker the Settings row uses). */
        const val OP_VOICE_NOTE_GRANT_OPEN = "voice_note_grant_open"

        /**
         * The receiver-side ops' one JSON envelope builder (the ops engine
         * builds its own per op): every outcome, startActivity failures
         * included, answers with one line of JSON on both channels, and a
         * body with quotes/backslashes must not produce malformed JSON.
         * The SPI grant activity shares it (TASK-826 review: three inline
         * copies were two too many).
         */
        internal fun ack(op: String, body: org.json.JSONObject.() -> Unit = {}): String =
            org.json.JSONObject().put("op", op).apply(body).toString()

        /** The always-works channel as a one-liner for the activity too. */
        internal fun logSpi(op: String, result: String) {
            Log.i(TAG, ack(op) { put("result", result) })
        }
    }

    /**
     * TASK-826: op=voice_note_grant_open. Starts the debug grant activity,
     * which fires the SAME OpenDocumentTree picker the Settings row uses
     * (initial URI included) and runs the shared acceptance policy on the
     * result. The DocumentsUI USE-THIS-FOLDER tap is the one gesture the
     * OS genuinely requires; after it the persisted grant survives
     * reinstalls, so a scripted trial never navigates Settings again.
     * Like nav, receiver-side: an activity start is beyond the JVM-pure
     * ops engine. The op is deliberately absent from TestSpiOps.OPS.
     */
    private fun handleVoiceNoteGrantOpen(context: Context, sourceToken: String?): String =
        runCatching {
            context.startActivity(
                android.content.Intent(
                    context, com.antivocale.app.testing.VoiceNoteGrantSpiActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    // WhatsApp Business support: value=whatsapp_business
                    // grants the Business slot; absent = WhatsApp.
                    .putExtra(com.antivocale.app.testing.VoiceNoteGrantSpiActivity.EXTRA_SOURCE, sourceToken)
            )
            ack(OP_VOICE_NOTE_GRANT_OPEN) {
                put("launched", true)
                // Review F5: name the slot; dual-slot trials must tell the
                // acks apart (absent token = the WhatsApp default).
                put("source", sourceToken ?: "whatsapp")
                put("note", "confirm USE THIS FOLDER in the DocumentsUI dialog; " +
                    "op=voice_note_state reports grantHeld/armed after")
            }
        }.getOrElse { e ->
            ack(OP_VOICE_NOTE_GRANT_OPEN) {
                put("error", "grant picker failed to launch: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    /**
     * TASK-486: op=nav dest=<token>. Starts MainActivity (singleTop) with the
     * debug-gated navigation extra; MainActivity routes the token to the tab
     * bar, a Settings sub-page, or a Settings section (expand + scroll).
     * Dest tokens are pinned by TestNavigationTest.
     */
    private fun handleNav(context: Context, dest: String?, scroll: String?): String {
        // Same envelope as the ops engine: every outcome - including a
        // startActivity failure - answers with JSON on both channels.
        return runCatching {
            val parsed = com.antivocale.app.ui.AppNavigation.parse(dest)
            if (parsed == null) {
                return ack("nav") {
                    put("error", "unknown dest '$dest' (expected tab:history|models|settings, " +
                        "models:import, models:gemma, settings:<section>, " +
                        "settings:<subpage>, or settings:<row>)")
                }
            }
            // TASK-815: a scroll extra only rides scrollable destinations;
            // anything else answers the error contract instead of a silent
            // no-op, and a malformed spec is named as such.
            if (scroll != null) {
                // Spec first: a malformed spec on a non-scrollable page must
                // name the typo, not the page.
                if (com.antivocale.app.ui.AppNavigation.parseScrollSpec(scroll) == null) {
                    return ack("nav") {
                        put("error", "invalid scroll '$scroll' (expected top, bottom, or a " +
                            "signed viewport count like +2 / -1)")
                    }
                }
                if (!com.antivocale.app.ui.AppNavigation.isScrollableDest(parsed)) {
                    val scrollable =
                        com.antivocale.app.ui.AppNavigation.scrollableDestTokens().joinToString("|")
                    return ack("nav") {
                        put("error", "scroll is not supported on '$dest' (scrollable: $scrollable)")
                    }
                }
            }
            context.startActivity(
                android.content.Intent(context, com.antivocale.app.MainActivity::class.java).apply {
                    putExtra(com.antivocale.app.ui.TestNavigation.EXTRA_TEST_NAV, dest)
                    if (scroll != null) {
                        putExtra(com.antivocale.app.ui.TestNavigation.EXTRA_TEST_NAV_SCROLL, scroll)
                    }
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            ack("nav") {
                put("dest", dest)
                if (scroll != null) put("scroll", scroll)
            }
        }.getOrElse { e ->
            ack("nav") { put("error", "nav failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_SPI) {
            Log.d(TAG, "Ignoring intent with action: ${intent.action}")
            return
        }
        // Belt-and-braces on top of the debug-only manifest registration.
        if (!BuildConfig.DEBUG) return

        val pendingResult = goAsync()
        val ops = TestSpiOps(
            preferencesManager, externalModelStore, importer, context.applicationContext,
            // TASK-736: the identity-cache dump op needs the live singleton.
            voiceNoteIdentityCache,
            // TASK-791: the unload op drives the production backend manager.
            backendManager,
            // TASK-826: op=voice_note_state reads the live armed flag;
            // TASK-827 adds the gate state beside it (the divergence of the
            // two is a catchable signal, not an invariant).
            { source -> voiceNoteDetectionCoordinators.forSource(source).armed },
            { source -> voiceNoteDetectionCoordinators.forSource(source).gateState.value })
        val op = intent.getStringExtra(EXTRA_OP)
        val key = intent.getStringExtra(EXTRA_KEY)
        val value = intent.getStringExtra(EXTRA_VALUE)
        val entry = intent.getStringExtra(EXTRA_ENTRY)
        val url = intent.getStringExtra(EXTRA_URL)
        val family = intent.getStringExtra(EXTRA_FAMILY)
        val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE)
        val dest = intent.getStringExtra(EXTRA_DEST)
        val scroll = intent.getStringExtra(EXTRA_SCROLL)

        // ModelPreloadReceiver idiom: goAsync plus a scope per receive,
        // finish() in finally. A local scope (not the @ApplicationScope one,
        // whose contract forbids cancelling) so the receive is fully done
        // once finish() returns.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
        scope.launch {
            try {
                // TASK-486: navigation is receiver-side (it needs an activity
                // start, which the JVM-pure ops engine cannot do). The ack
                // mirrors the ops JSON shape. TASK-826: same story for the
                // grant picker.
                val json = if (op == "nav") {
                    handleNav(context, dest, scroll)
                } else if (op == OP_VOICE_NOTE_GRANT_OPEN) {
                    handleVoiceNoteGrantOpen(context, intent.getStringExtra(EXTRA_VALUE))
                } else {
                    // handle() answers every request with JSON, errors included.
                    ops.handle(op, key, value, entry, url, family, modelType)
                }
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
