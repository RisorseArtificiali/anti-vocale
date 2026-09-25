package com.antivocale.app.receiver

import android.app.Application
import android.content.Context
import android.content.Intent
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * TASK-274: the consent gate on the PRELOAD_MODEL half of the automation
 * surface (the TaskerRequestReceiver half is pinned in
 * [TaskerRequestReceiverNotificationTest]; Hilt overwrites injected fields, so
 * both classes flip the REAL preference through the same entry point).
 *
 * The receiver answers on a real Dispatchers.IO coroutine behind goAsync, so
 * the tests poll the application's sent broadcasts for the PRELOAD_RESULT
 * intent instead of reading synchronously. Not pinnable in this harness, stated
 * honestly: the gate-vs-isReady ordering when a model IS loaded (Robolectric
 * boots with the LLM manager not ready, so a reordering bug that only shows
 * with isReady()==true stays invisible here; the gate-before-modelPath arm is
 * covered by the gate-open test below landing on NO_MODEL_CONFIGURED).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ModelPreloadReceiverGateTest {

    private lateinit var context: Context

    private fun appPreferences(): PreferencesManager =
        EntryPointAccessors.fromApplication(context, SubtitlePrefsEntryPoint::class.java).preferencesManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        runBlocking { appPreferences().saveExternalAutomationEnabled(false) }
    }

    @After
    fun tearDown() {
        // The class flips the REAL preference; restore the shipped default.
        runBlocking { appPreferences().saveExternalAutomationEnabled(false) }
    }

    /** Waits for the receiver's IO coroutine to emit its (at most one) reply. */
    private fun awaitPreloadReply(timeoutMs: Long = 5000): Intent? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val reply = shadowOf(context.applicationContext as Application).broadcastIntents
                .lastOrNull { it.action == "com.antivocale.app.PRELOAD_RESULT" }
            if (reply != null) return reply
            Thread.sleep(20)
        }
        return null
    }

    private fun fire(silent: Boolean) {
        ModelPreloadReceiver().onReceive(
            context,
            Intent(ModelPreloadReceiver.ACTION_PRELOAD_MODEL).apply {
                if (silent) putExtra(ModelPreloadReceiver.EXTRA_SILENT, true)
            })
    }

    @Test
    fun `gate off rejects with AUTOMATION_DISABLED naming the setting`() {
        fire(silent = false)

        val reply = awaitPreloadReply()
        assertNotNull("the rejection must answer (the caller learns what to flip)", reply)
        assertEquals("AUTOMATION_DISABLED", reply!!.getStringExtra("status"))
        assertEquals(
            context.getString(R.string.external_automation_disabled),
            reply.getStringExtra("message"))
    }

    @Test
    fun `gate off with silent=true sends no reply at all`() {
        fire(silent = true)

        Thread.sleep(300)
        assertNull("the silent extra suppresses even the rejection reply",
            shadowOf(context.applicationContext as Application).broadcastIntents
                .lastOrNull { it.action == "com.antivocale.app.PRELOAD_RESULT" })
    }

    @Test
    fun `gate on lets the request proceed past the consent check`() {
        // The gate-open proof: with no model configured in the test app, a
        // request that passes the gate lands on NO_MODEL_CONFIGURED; if the
        // gate still rejected, the status would be AUTOMATION_DISABLED.
        runBlocking { appPreferences().saveExternalAutomationEnabled(true) }
        fire(silent = false)

        val reply = awaitPreloadReply()
        assertNotNull(reply)
        assertEquals("NO_MODEL_CONFIGURED", reply!!.getStringExtra("status"))
    }

    @Test
    fun `the rejection reply stays package-scoped when no sender is named`() {
        // Below API 34 (this class runs sdk 31) no sender is known: the reply
        // falls back to the app's own package (the reply-sink posture).
        fire(silent = false)

        val reply = awaitPreloadReply()
        assertNotNull(reply)
        assertEquals(context.packageName, reply!!.`package`)
    }
}
