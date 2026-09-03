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
        assertTrue(ShareReceiverActivity.NOTIFICATION_ID_BAND_BASE + ShareReceiverActivity.NOTIFICATION_ID_BAND_RANGE - 1 < base)
    }

    @Test
    fun `tasker fallback ids stay inside their reserved band`() {
        listOf("tasker-a", "unknown_1725300000000", "").forEach { taskId ->
            val id = TaskerRequestReceiver.fallbackNotificationId(taskId)
            assertTrue("id $id for taskId '$taskId' outside 2201..2300", id in 2201..2300)
        }
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
        // String.hashCode: masked ids 2479 and 2418. Both source hashes are
        // negative, so the pins also catch an abs() variant of the mask
        // (it would produce 2471 and 2432 instead).
        assertEquals(2479, ShareReceiverActivity.choiceNotificationId("share_1725300000000"))
        assertEquals(2418, ShareReceiverActivity.errorNotificationId("No audio file"))
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
