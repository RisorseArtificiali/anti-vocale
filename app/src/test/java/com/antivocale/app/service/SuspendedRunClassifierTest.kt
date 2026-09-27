package com.antivocale.app.service

import android.app.ApplicationExitInfo
import com.antivocale.app.service.SuspendedRunClassifier.Evidence
import com.antivocale.app.util.NativeCrashDetector
import com.antivocale.app.service.SuspendedRunClassifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-684 (GH #109): the freezer-suspension state machine. The decision
 * table every real death maps onto: suspension is claimed ONLY on the
 * heartbeat-to-death gap; the crash lineage wins; everything unprovable
 * errs to no-label. The 2026-09-25 lesson (TASK-521: a slow-but-alive phase
 * misread as a freezer stall) is pinned by the fresh-heartbeat cases.
 */
class SuspendedRunClassifierTest {

    private val task = "task-1"
    private val gap = SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS

    /**
     * The default, fully-provable freezer shape: matching heartbeat, silent
     * long before death. Simplify F1: the pairs arrive as their own types.
     */
    private fun freezerEvidence(silentMs: Long, reason: Int) = Evidence(
        processingTaskId = task,
        heartbeat = RunHeartbeat.Snapshot(taskId = task, timestampMs = 1_000_000L),
        exit = NativeCrashDetector.ExitRecord(
            reason = reason, timestamp = 1_000_000L + silentMs, description = null),
    )

    @Test
    fun `heartbeat silent long before death is a suspension`() {
        val v = SuspendedRunClassifier.classify(freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(Verdict.Suspended(task, gap), v)
    }

    @Test
    fun `OEM killers surface as REASON_SIGNALED and ANR deaths classify too`() {
        // NativeCrashDetector's own docs: ColorOS/MIUI killers land on
        // REASON_SIGNALED; a frozen app can also die ANR. Both carry no crash
        // signature, so the gap decides.
        listOf(
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_OTHER,
        ).forEach { reason ->
            assertEquals(
                "reason=$reason",
                Verdict.Suspended(task, 10 * gap),
                SuspendedRunClassifier.classify(freezerEvidence(10 * gap, reason)))
        }
    }

    @Test
    fun `gap below the threshold is never a suspension`() {
        // One missed tick interval, or a handful: an alive process can jitter
        // this much; the threshold demands far beyond any alive-phase delta.
        val v = SuspendedRunClassifier.classify(freezerEvidence(gap - 1, ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(Verdict.Interrupted, v)
    }

    @Test
    fun `the 2026-09-25 lesson holds and alive at death stays interrupted, never suspended`() {
        // TASK-521: the run read as a freezer stall was the slow summary
        // phase with the process alive. The phase-blind heartbeat keeps
        // ticking while a phase is slow, so the last tick sits right at the
        // death and the classifier must claim nothing.
        val v = SuspendedRunClassifier.classify(freezerEvidence(RunHeartbeat.TICK_MS, ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(Verdict.Interrupted, v)
    }

    @Test
    fun `native crash lineage wins over any gap`() {
        listOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
        ).forEach { reason ->
            assertEquals(
                "reason=$reason",
                Verdict.NativeCrashLineage,
                SuspendedRunClassifier.classify(freezerEvidence(100 * gap, reason)))
        }
    }

    @Test
    fun `no heartbeat is no label`() {
        // The run never ticked, or it ended normally and cleared the
        // heartbeat: nothing separates a freezer from a finished run.
        val v = SuspendedRunClassifier.classify(
            freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED).copy(heartbeat = null))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `heartbeat without its task id is no label`() {
        val v = SuspendedRunClassifier.classify(
            freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED).copy(heartbeat = null))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `no orphaned PROCESSING row is no label`() {
        val v = SuspendedRunClassifier.classify(
            freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED).copy(processingTaskId = null))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `heartbeat naming a different row is no label`() {
        val v = SuspendedRunClassifier.classify(
            freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED).copy(processingTaskId = "other-row"))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `no exit record is no label even with a stale heartbeat`() {
        // Below API 30, or empty history. Without the death timestamp the gap
        // could only be measured against "now", which would blame a user who
        // reopened the app hours after an outright kill.
        val v = SuspendedRunClassifier.classify(
            Evidence(
                processingTaskId = task,
                heartbeat = RunHeartbeat.Snapshot(taskId = task, timestampMs = 1_000_000L),
                exit = null,
            ))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `heartbeat after death means the clock moved, so no label`() {
        val v = SuspendedRunClassifier.classify(
            freezerEvidence(gap, ApplicationExitInfo.REASON_SIGNALED).copy(heartbeat = RunHeartbeat.Snapshot(taskId = task, timestampMs = 5_000_000L)))
        assertEquals(Verdict.Unknown, v)
    }

    @Test
    fun `suspended carries the gap as the lower bound`() {
        val v = SuspendedRunClassifier.classify(freezerEvidence(7 * gap + 123L, ApplicationExitInfo.REASON_SIGNALED))
        assertTrue(v is Verdict.Suspended)
        assertEquals(7 * gap + 123L, (v as Verdict.Suspended).atLeastMs)
    }

    @Test
    fun `threshold is far beyond any alive-phase delta`() {
        // 24 tick intervals: pinned so a future edit cannot quietly lower the
        // bar to "a few missed ticks" and re-open the false-label class.
        assertEquals(24 * RunHeartbeat.TICK_MS, SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS)
        assertEquals(120_000L, SuspendedRunClassifier.SUSPENDED_MIN_GAP_MS)
    }
}
