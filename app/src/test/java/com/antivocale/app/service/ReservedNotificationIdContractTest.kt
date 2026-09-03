package com.antivocale.app.service

import com.antivocale.app.receiver.ShareReceiverActivity
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.work.SubtitleChoiceTimeoutWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserved-notification-id contract (TASK-329/440), in one place: the
 * allocator owns every id at or above ResultNotificationFactory's base, every
 * fixed or banded producer stays below it, and every per-key derivation folds
 * through the shared bandedNotificationId helper. Split out of
 * ResultNotificationFactoryTest when the second band landed, so the builder
 * tests stay about layout and these stay about ids. Plain JUnit: companion
 * members only, no Context.
 */
class ReservedNotificationIdContractTest {

    @Test
    fun `allocator hands out consecutive unique ids`() {
        val first = ResultNotificationFactory.nextNotificationId()
        val second = ResultNotificationFactory.nextNotificationId()
        assertEquals(first + 1, second)
        // Not asserting the absolute value: earlier tests in the same process may draw ids.
    }

    @Test
    fun `allocator seeds at the reserved base and never dips below it`() {
        // The seed doubles as the first id of a fresh process; pinning the
        // absolute value keeps the reserved-range contract (BASE KDoc table)
        // visible in every review of a change to it.
        assertEquals(3000, ResultNotificationFactory.RESULT_NOTIFICATION_ID_BASE)
        val id = ResultNotificationFactory.nextNotificationId()
        assertTrue(id >= ResultNotificationFactory.RESULT_NOTIFICATION_ID_BASE)
    }

    @Test
    fun `reserved base sits above every fixed and banded notification id`() {
        val base = ResultNotificationFactory.RESULT_NOTIFICATION_ID_BASE
        assertTrue(InferenceService.NOTIFICATION_ID < base)
        assertTrue(SubtitleChoiceTimeoutWorker.NOTIFICATION_ID < base)
        assertTrue(ExtractionService.NOTIFICATION_ID_BASE + ExtractionService.NOTIFICATION_ID_RANGE - 1 < base)
        assertTrue(ShareReceiverActivity.CHOICE_ID_BAND_BASE + ShareReceiverActivity.CHOICE_ID_BAND_RANGE - 1 < base)
        assertTrue(ShareReceiverActivity.ERROR_ID_BAND_BASE + ShareReceiverActivity.ERROR_ID_BAND_RANGE - 1 < base)
        assertTrue(TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_BASE + TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_RANGE - 1 < base)
    }

    /**
     * Band-vs-band disjointness, the check the original contract test lacked
     * (review 2026-09-03: moving a band INSIDE another band compiled and
     * passed every existing test). Every fixed and banded producer interval,
     * pairwise disjoint, expressed from the live constants.
     */
    @Test
    fun `every notification id band is pairwise disjoint`() {
        val intervals = listOf(
            "inference-foreground" to (InferenceService.NOTIFICATION_ID..InferenceService.NOTIFICATION_ID),
            "worker-foreground" to (SubtitleChoiceTimeoutWorker.NOTIFICATION_ID..SubtitleChoiceTimeoutWorker.NOTIFICATION_ID),
            "download-band" to (ExtractionService.NOTIFICATION_ID_BASE until
                ExtractionService.NOTIFICATION_ID_BASE + ExtractionService.NOTIFICATION_ID_RANGE),
            "tasker-fallback-band" to (TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_BASE until
                TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_BASE + TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_RANGE),
            "share-choice-band" to (ShareReceiverActivity.CHOICE_ID_BAND_BASE until
                ShareReceiverActivity.CHOICE_ID_BAND_BASE + ShareReceiverActivity.CHOICE_ID_BAND_RANGE),
            "share-error-band" to (ShareReceiverActivity.ERROR_ID_BAND_BASE until
                ShareReceiverActivity.ERROR_ID_BAND_BASE + ShareReceiverActivity.ERROR_ID_BAND_RANGE),
        )
        for (i in intervals.indices) for (j in i + 1 until intervals.size) {
            val (nameA, a) = intervals[i]
            val (nameB, b) = intervals[j]
            assertTrue(
                "bands '$nameA' $a and '$nameB' $b overlap",
                a.first >= b.last + 1 || b.first >= a.last + 1,
            )
        }
    }

    @Test
    fun `tasker fallback ids are sequential and stay inside their reserved band`() {
        // Sequential, not hash-derived: the fallback notification is the sole
        // carrier of a pending request, so two concurrent posts must NEVER
        // share a slot (hash folds collide deterministically for sequential
        // Tasker ids: task_8/task_40 at any modulus tried; review 2026-09-03).
        val base = TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_BASE
        val band = base until base + TaskerRequestReceiver.FALLBACK_NOTIFICATION_ID_RANGE
        val ids = (1..20).map { TaskerRequestReceiver.fallbackNotificationId() }
        ids.forEach { assertTrue("id $it outside $band", it in band) }
        assertEquals("concurrent fallback posts must never share a slot", ids.size, ids.toSet().size)
    }

    @Test
    fun `share receiver ids stay inside their reserved band`() {
        // Live constants, not literals, so the band follows its definition
        // (TASK-329 simplify finding: a stale-able literal top is worthless).
        val base = ShareReceiverActivity.NOTIFICATION_ID_BAND_BASE
        val band = base until base + ShareReceiverActivity.NOTIFICATION_ID_BAND_RANGE
        listOf("share_1725300000000", "share_1", "").forEach { taskId ->
            val id = ShareReceiverActivity.choiceNotificationId(taskId)
            assertTrue("choice id $id for taskId '$taskId' outside $band", id in band)
        }
        listOf("No audio file", "Storage full", "").forEach { message ->
            val id = ShareReceiverActivity.errorNotificationId(message)
            assertTrue("error id $id for message '$message' outside $band", id in band)
        }
    }

    @Test
    fun `share receiver ids are stable for the same input`() {
        // The choice prompt is cancelled/replaced by taskId in
        // SubtitleChoiceTimeoutWorker and NotificationActionReceiver; all three
        // sites go through choiceNotificationId, so an exact pin catches any
        // drift to a second derivation. Expected values verified against Java's
        // String.hashCode over the two SUB-BANDS (choice 2401..2450,
        // error 2451..2500): choice 2429, error 2468. Both source hashes are
        // negative, so the pins also catch an abs() variant of the mask.
        assertEquals(2429, ShareReceiverActivity.choiceNotificationId("share_1725300000000"))
        assertEquals(2468, ShareReceiverActivity.errorNotificationId("No audio file"))
    }

    @Test
    fun `band helper keeps every Int hash inside the band`() {
        // abs(Int.MIN_VALUE) is negative and would push an id below the band;
        // the mask is what keeps every possible hash inside (the subtlety a
        // copied formula loses). For ordinary negative hashes an abs variant
        // still lands inside the band, so only this corner plus the exact pins
        // above can catch it.
        val base = ShareReceiverActivity.NOTIFICATION_ID_BAND_BASE
        val band = base until base + ShareReceiverActivity.NOTIFICATION_ID_BAND_RANGE
        listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE).forEach { hash ->
            val id = ResultNotificationFactory.bandedNotificationId(
                hash, base, ShareReceiverActivity.NOTIFICATION_ID_BAND_RANGE
            )
            assertTrue("id $id for hash $hash outside $band", id in band)
        }
    }
}
