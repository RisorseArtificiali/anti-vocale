package com.antivocale.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.local.LogDao
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.transcription.TranscriptionOrchestrator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * TASK-389: deterministic proof of the cda1353 service-teardown vs
 * pending-result-notification race class on the window itself, not on a
 * worker. The window is the gap between the queue's "no more work" decision
 * (processQueue()'s finally, `_isTranscribing = false`) and the result
 * notification post at the end of onSuccess's share-request coroutine. A
 * result landing inside that frozen window must still produce its
 * notification:
 *
 *  - Test A (damage, guard ABSENT): a minimal replica of the pre-cda1353
 *    structure (git show cda1353^): onSuccess fired an UNTRACKED
 *    serviceScope.launch, and processQueue()'s tail went straight to
 *    stopForeground + stopSelf (which in real Android delivers onDestroy ->
 *    serviceScope.cancel). With the notification coroutine frozen on
 *    auto-copy's preference read, the teardown cancels it underneath; the
 *    result then lands inside the window and the notification is never
 *    posted: the TASK-336 symptom ("transcription succeeded, nothing
 *    arrived").
 *
 *  - Test B (fix, guard PRESENT): the real InferenceService driven through
 *    its lifecycle (onCreate + onStartCommand) with a mocked orchestrator
 *    that calls listener.onSuccess from inside processRequest exactly as
 *    production does. The notification coroutine's first suspension
 *    (autoCopyIfEnabled's DataStore read) is frozen through the mocked
 *    flow, and the same interleaving is pinned: teardown decision observed
 *    (public companion isTranscribing flow) while the result is still
 *    pending. cda1353's pendingResultNotifications join then holds
 *    stopForeground/stopSelf until the notification has posted; the drain
 *    finishes only after the post, and the notification survives the
 *    subsequent onDestroy.
 *
 * Determinism: no Thread.sleep, no polling. Freezes are
 * CompletableDeferred/CountDownLatch gates, the interleaving order is pinned
 * by awaits only, and every bounded await is a failure path, never a pass
 * condition.
 *
 * Why the reflection read of `currentProcessingJob` in Test B instead of a
 * seam or a subclass: stopSelf() and stopForeground(int) are final on
 * android.app.Service, so no test subclass can intercept them, and the
 * service exposes no teardown-completion signal. processQueue() assigns the
 * drain Job to that field synchronously before onStartCommand returns, and
 * the launch block ends with stopForeground + stopSelf, so joining that Job
 * IS the deterministic "teardown finished" latch. Read-only reflection
 * (the house precedent is SincResamplerTest's private-method access); an
 * internal-var seam was rejected because it would expose mutation of a
 * lifecycle-central Job field to all production code for a test-only need.
 * The stand-in + real-component pair follows NativeKeepAliveRaceTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InferenceServiceTeardownRaceTest {

    private companion object {
        const val RESULT_TEXT = "una trascrizione velocissima"
        const val TIMEOUT_MS = 10_000L
    }

    /**
     * Minimal replica of the PRE-cda1353 service structure. Only the
     * race-relevant core is kept: the transcription task is already finished
     * when onSuccess runs (a fast single-chunk job), so the only ordering
     * that matters is notification-coroutine progress vs queue teardown.
     */
    private class PreCda1353ServiceStandIn(private val scope: CoroutineScope) {
        val notifyPosted = AtomicBoolean(false)

        /** true once stopForeground + stopSelf completed (the service is tearing down). */
        val teardownRan = AtomicBoolean(false)
        var notificationJob: Job? = null
            private set

        /**
         * PRE-fix onSuccess for a share request: fire-and-forget
         * serviceScope.launch, nothing tracks the job. The freeze stands in
         * for autoCopyIfEnabled's DataStore read (the coroutine's first
         * suspension, upstream of showResultNotification's notify).
         */
        fun onSuccessShareRequest(readStarted: CompletableDeferred<Unit>, resultArrival: CompletableDeferred<Unit>) {
            notificationJob = scope.launch {
                readStarted.complete(Unit)
                resultArrival.await()
                // saveTranscriptToFileIfEnabled: elided, downstream of the race window
                notifyPosted.set(true) // showResultNotification's notify()
            }
        }

        /**
         * PRE-fix processQueue tail: no join. finally { _isTranscribing =
         * false } ran (no more work), then straight to stopForeground +
         * stopSelf; the system answers stopSelf with onDestroy, which cancels
         * serviceScope and everything parked in it.
         */
        fun queueTeardownThenDestroy() {
            teardownRan.set(true)
            scope.cancel() // stopSelf -> onDestroy -> serviceScope.cancel()
        }
    }

    @Test
    fun `damage state is reachable when the guard is absent`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob()) // the pre-fix serviceScope
        val standIn = PreCda1353ServiceStandIn(scope)
        val readStarted = CompletableDeferred<Unit>()
        val resultArrival = CompletableDeferred<Unit>()

        // Fast single-chunk job: the task finished and onSuccess fired its
        // untracked notification coroutine, which is now parked mid-body on
        // its first suspension.
        standIn.onSuccessShareRequest(readStarted, resultArrival)
        readStarted.await() // window frozen: notification pending, result not yet delivered

        // The queue teardown wins the race exactly as in TASK-336: with no
        // join, the drain proceeds straight to stopSelf -> onDestroy ->
        // serviceScope.cancel, killing the parked notification coroutine.
        standIn.queueTeardownThenDestroy()
        assertTrue("the drain must have reached teardown first (pre-fix has no join)", standIn.teardownRan.get())
        assertTrue(
            "teardown must have cancelled the in-flight notification job",
            standIn.notificationJob!!.isCancelled,
        )

        // The transcription result lands INSIDE the frozen window: the read
        // completes, but its continuation is already dead.
        resultArrival.complete(Unit)
        advanceUntilIdle()

        // The TASK-336 symptom, deterministically: transcription succeeded
        // (the result arrived) yet the result notification was never posted.
        assertFalse("the lost-notification state must be reachable pre-fix", standIn.notifyPosted.get())
    }

    @Test
    fun `production join closes the same window`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        // The freeze: autoCopyIfEnabled's autoCopyEnabled.first() is the
        // notification coroutine's first suspension. The mocked flow parks
        // the coroutine there until the test releases it.
        val autoCopyArrived = CountDownLatch(1)
        val releaseAutoCopy = CompletableDeferred<Unit>()
        val preferences = mockk<PreferencesManager>()
        every { preferences.autoCopyEnabled } returns flow {
            autoCopyArrived.countDown()
            releaseAutoCopy.await()
            emit(false) // auto-copy off: skip the clipboard/toast machinery
        }
        every { preferences.outputFolderUri } returns flowOf(null) // auto-save disabled

        // The orchestrator reports success from inside processRequest, on the
        // task job's coroutine, exactly as production does. isShareRequest is
        // what routes onSuccess into the result-notification coroutine.
        val orchestrator = mockk<TranscriptionOrchestrator>(relaxed = true)
        coEvery {
            orchestrator.processRequest(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
            )
        } coAnswers {
            val listener = args.filterIsInstance<TranscriptionListener>().first()
            listener.onSuccess(
                taskId = args[0] as String, // first parameter of processRequest
                resultText = RESULT_TEXT,
                isShareRequest = true,
                sourcePackage = null, // per-app prefs path never engages: fewer mocks
                durationMs = 1234L,
            )
            Result.success(RESULT_TEXT)
        }

        inject(service, "preferencesManager", preferences)
        inject(service, "perAppPreferencesManager", mockk<PerAppPreferencesManager>(relaxed = true))
        inject(service, "orchestrator", orchestrator)
        inject(service, "logDao", mockk<LogDao>(relaxed = true))

        val intent = Intent().apply {
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "race-1")
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "audio")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "")
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, "/nonexistent/race.wav")
            putExtra(InferenceService.EXTRA_SOURCE, "share")
        }
        service.onStartCommand(intent, 0, 1)
        val drainJob = drainJobOf(service)

        // Window entered: the notification coroutine is alive inside its
        // first suspension. This also proves the drain already set
        // _isTranscribing = true (it does so before launching the task job).
        assertTrue("notification coroutine never reached its freeze", autoCopyArrived.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))

        // Teardown decision made: the drain left its loop and final block.
        // The companion flow is public state set immediately before the
        // pendingResultNotifications snapshot + join.
        runBlocking { withTimeout(TIMEOUT_MS) { InferenceService.isTranscribing.first { !it } } }

        // THE WINDOW: "no more work" decided, result still pending. The join
        // must hold the teardown here; pre-fix this is exactly where
        // stopForeground + stopSelf had already run (Test A's damage).
        val serviceShadow = Shadows.shadowOf(service)
        assertFalse("the service must not stopSelf while the result notification is pending", serviceShadow.isStoppedBySelf)
        assertFalse("the service must not leave the foreground while the result notification is pending", serviceShadow.isForegroundStopped)
        assertTrue("no result notification may be posted yet (the job is frozen mid-body)", resultNotificationsOn(service).isEmpty())

        // The result lands inside the frozen window (the TASK-336 timing).
        releaseAutoCopy.complete(Unit)

        // The drain's launch block ends with stopForeground + stopSelf AFTER
        // the join, so drainJob completion means: notification job completed
        // (its notify() ran) and then teardown executed.
        runBlocking { withTimeout(TIMEOUT_MS) { drainJob.join() } }

        assertTrue("the join must release teardown once the notification completed (no deadlock)", serviceShadow.isStoppedBySelf)
        val posted = resultNotificationsOn(service)
        assertEquals("exactly one result notification, never lost", 1, posted.size)
        assertEquals(RESULT_TEXT, posted[0].extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        // The system destroys the service right after stopSelf: the
        // notification was posted to a live context and survives the scope
        // cancellation (pre-fix it was the coroutine that died instead).
        controller.destroy()
        assertEquals("nothing may post to the destroyed service either", 1, resultNotificationsOn(service).size)
    }

    // ---- helpers ----

    /** Posts the given [value] into one of the service's @Inject lateinit fields. */
    private fun inject(service: InferenceService, name: String, value: Any?) {
        val field = InferenceService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    /**
     * The drain coroutine created by processQueue(). Assigned synchronously
     * before onStartCommand returns and never cleared on the happy path; its
     * completion is the teardown completion (see class KDoc for why this
     * handle instead of a seam).
     */
    private fun drainJobOf(service: InferenceService): Job {
        val field = InferenceService::class.java.getDeclaredField("currentProcessingJob")
        field.isAccessible = true
        return field.get(service) as Job
    }

    private fun resultNotificationsOn(service: InferenceService): List<Notification> =
        Shadows.shadowOf(service.getSystemService(NotificationManager::class.java))
            .allNotifications
            .filter { it.channelId == InferenceService.RESULT_CHANNEL_ID }
}
