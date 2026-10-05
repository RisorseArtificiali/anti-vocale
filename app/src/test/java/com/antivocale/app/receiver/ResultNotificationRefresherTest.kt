package com.antivocale.app.receiver

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.service.TranscriptPager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Tests the prev/next re-post logic, called directly (the goAsync wrapper is covered on-device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ResultNotificationRefresherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Whole pages of "parola" words: n words occupy 7n - 1 chars (see plan conventions). */
    private fun longText(pages: Int): String {
        val perPage = (TranscriptPager.PAGE_CHARS + 1) / 7
        return List(pages * perPage) { "parola" }.joinToString(" ")
    }

    private fun pageIntent(action: String, text: String, page: Int, id: Int = 7): Intent =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, text)
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, page)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, "org.telegram.messenger")
            putExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, 123_456L)
        }

    private fun shadowNm() =
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun postedNotification(id: Int): Notification =
        requireNotNull(shadowNm().getNotification(id)) { "no notification posted with id $id" }

    private fun subTextOf(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Test
    fun `next from page 0 re-posts same id at page 1`() = runBlocking {
        val text = longText(3)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 0))
        val n = postedNotification(7)
        assertEquals("Page 2 of 3", subTextOf(n))
        assertTrue(n.actions!!.map { it.title }.contains("Previous"))
    }

    @Test
    fun `a repost keeps the status facts that rode the nav intent (TASK-758)`() = runBlocking {
        val text = longText(3)
        val intent = pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 0).apply {
            putExtra(NotificationActionReceiver.EXTRA_REPETITION_SUSPECTED, true)
            putExtra(NotificationActionReceiver.EXTRA_COPIED_TO_CLIPBOARD, true)
            putExtra(NotificationActionReceiver.EXTRA_STREAMED_WITHOUT_VAD, true)
            putExtra(NotificationActionReceiver.EXTRA_REFINED_FROM, "whisper")
            putExtra(NotificationActionReceiver.EXTRA_NOT_REFINED, true)
        }
        ResultNotificationRefresher.refresh(context, intent)
        val sub = requireNotNull(subTextOf(postedNotification(7))).lowercase()
        // Every fact still renders after the page change; the read side of
        // the round-trip is what drops them when a field goes unwired.
        assertTrue(sub.contains("repetition"))
        assertTrue(sub.contains("copied"))
        assertTrue(sub.contains("refined"))
    }

    @Test
    fun `prev from page 0 stays at page 0`() = runBlocking {
        val text = longText(2)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_PREV, text, 0))
        assertEquals("Page 1 of 2", subTextOf(postedNotification(7)))
    }

    @Test
    fun `next clamps at the last page`() = runBlocking {
        val text = longText(2)
        ResultNotificationRefresher.refresh(context, pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 1))
        assertEquals("Page 2 of 2", subTextOf(postedNotification(7)))
    }

    @Test
    fun `missing notification id is a no-op`() = runBlocking {
        val intent = pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, longText(2), 0, id = -1)
        ResultNotificationRefresher.refresh(context, intent)
        assertEquals(0, shadowNm().allNotifications.size)
    }

    @Test
    fun `missing text is a no-op`() = runBlocking {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PAGE_NEXT
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, 0)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, 7)
        }
        ResultNotificationRefresher.refresh(context, intent)
        assertEquals(0, shadowNm().allNotifications.size)
    }

    @Test
    fun `repost keeps the quick share back action from per-app prefs`() = runBlocking {
        // Regression test for the DataStore double-activation bug (TASK-327): the refresher
        // constructed its own PerAppPreferencesManager, whose instance-level DataStore delegate
        // collided with the Hilt singleton's on the same file, silently forcing default prefs.
        val manager = com.antivocale.app.data.PerAppPreferencesManager(context)
        manager.updatePreferencesForPackage("org.telegram.messenger") {
            copy(showShareAction = true, quickShareBack = true)
        }
        ResultNotificationRefresher.refresh(
            context,
            pageIntent(NotificationActionReceiver.ACTION_PAGE_PREV, longText(2), 1)
        )
        val titles = postedNotification(7).actions!!.map { it.title.toString() }
        assertTrue("expected quick-share-back action, got $titles", titles.contains("Send to Telegram"))
    }

    @Test
    fun `oversized text page action is a no-op`() = runBlocking {
        val text = List(9_000) { "parola" }.joinToString(" ") // 62_999 chars, above MAX_PAGED_LENGTH
        ResultNotificationRefresher.refresh(
            context,
            pageIntent(NotificationActionReceiver.ACTION_PAGE_NEXT, text, 0)
        )
        assertEquals(0, shadowNm().allNotifications.size)
    }
}
