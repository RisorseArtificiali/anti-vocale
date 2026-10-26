package com.antivocale.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which completed requests post the result notification. Shares always did
 * (TASK-688); automatic voice notes (WhatsApp and WhatsApp Business) now do
 * too, since the zero-tap route otherwise leaves the result discoverable
 * only by opening History. Every other origin keeps its silence.
 */
class ResultNotificationPolicyTest {

    @Test
    fun `shares and automatic voice notes post the result notification`() {
        assertTrue(InferenceService.postsResultNotification(isShareRequest = true, InferenceService.SOURCE_SHARE))
        assertTrue(InferenceService.postsResultNotification(isShareRequest = false, InferenceService.SOURCE_VOICE_NOTE))
    }

    @Test
    fun `in-app and automation origins stay without a result notification`() {
        listOf(
            InferenceService.SOURCE_BROWSE,
            InferenceService.SOURCE_FOLDER_WATCH,
            InferenceService.SOURCE_RETRANSCRIBE,
            InferenceService.SOURCE_BENCHMARK,
            // Tasker requests carry no source.
            null,
        ).forEach { source ->
            assertFalse("source=$source", InferenceService.postsResultNotification(isShareRequest = false, source))
        }
    }

    /**
     * TASK-889: a cold-start sweep note is one of a burst; the per-note
     * SUCCESS post would stack N notifications on a 3-slot shade, so the
     * sweep's digest replaces them. The History row and auto-save are not
     * gated by this policy; only the notification is.
     */
    @Test
    fun `a catch-up sweep note suppresses the per-note result notification`() {
        assertFalse(InferenceService.postsResultNotification(
            isShareRequest = false, InferenceService.SOURCE_VOICE_NOTE, catchup = true))
        // The flag off (every live detection) keeps the zero-tap promise.
        assertTrue(InferenceService.postsResultNotification(
            isShareRequest = false, InferenceService.SOURCE_VOICE_NOTE, catchup = false))
    }
}
