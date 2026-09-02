package com.antivocale.app.service

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.receiver.NotificationActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Robolectric tests for the shared result-notification builder (TASK-327). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ResultNotificationFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = ResultNotificationFactory(context)

    /** Share action on, quick share back to Telegram, matching the triggering user scenario. */
    private val prefs = AppNotificationPreferences(
        autoCopy = false, showShareAction = true,
        notificationSound = "default", quickShareBack = true
    )

    /** Whole pages of "parola" words: n words occupy 7n - 1 chars (see plan conventions). */
    private fun longText(pages: Int): String {
        val perPage = (TranscriptPager.PAGE_CHARS + 1) / 7
        return List(pages * perPage) { "parola" }.joinToString(" ")
    }

    private fun spec(
        text: String,
        page: Int = 0,
        repost: Boolean = false,
        sourcePackage: String = "org.telegram.messenger"
    ) = ResultNotificationSpec(
        transcriptionText = text,
        taskId = "task-1",
        sourcePackage = sourcePackage,
        confidence = 0.9f,
        detectedLanguage = null,
        notificationId = 5_000,
        pageIndex = page,
        firstPostedAt = 1_000L,
        repost = repost
    )

    private fun langSpec(text: String, page: Int = 0) = ResultNotificationSpec(
        transcriptionText = text,
        taskId = "task-1",
        sourcePackage = "org.telegram.messenger",
        confidence = 0.3f,
        detectedLanguage = "it",
        notificationId = 5_000,
        pageIndex = page,
        firstPostedAt = 1_000L
    )

    private fun Notification.titles(): List<String> =
        actions?.map { it.title.toString() }.orEmpty()

    private fun Notification.contentViewText(): String? =
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun Notification.subTextCompat(): String? =
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Test
    fun `allocator hands out consecutive unique ids`() {
        val first = ResultNotificationFactory.nextNotificationId()
        val second = ResultNotificationFactory.nextNotificationId()
        assertEquals(first + 1, second)
        // Not asserting the absolute value: earlier tests in the same process may draw ids.
    }

    @Test
    fun `short single page text keeps today's layout`() {
        val text = "ciao come stai"
        val n = factory.build(spec(text), prefs)
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
        assertEquals(text, n.contentViewText())
        assertNull(n.subTextCompat())
    }

    /**
     * TASK-433: the wiring (source package -> setPackage + action title) works
     * for fork sources. Two representatives suffice here: the full per-fork
     * census (names + targets + flavor suffixes + uncensused fallbacks) is
     * pinned at the table level in AppInfoUtilsKnownNamesTest.
     */
    @Test
    fun `telegram forks share back to the official telegram target`() {
        val forks = listOf(
            "com.radolyn.ayugram" to "AyuGram",
            "org.telegram.messenger.web" to "Telegram"
        )
        forks.forEach { (source, label) ->
            val n = factory.build(spec("ciao", sourcePackage = source), prefs)
            val action = n.actions!!.first { it.title.toString() == "Send to $label" }
            assertEquals(
                "share-back target for $source",
                "org.telegram.messenger",
                Shadows.shadowOf(action.actionIntent).savedIntent.`package`
            )
        }
    }

    /** TASK-433: the family table must reproduce the old when block exactly. */
    @Test
    fun `share back target keeps the pre-TASK-433 family mappings`() {
        // WhatsApp family flavors normalize to the canonical client.
        val w4b = factory.build(spec("ciao", sourcePackage = "com.whatsapp.w4b"), prefs)
        val w4bAction = w4b.actions!!.first { it.title.toString() == "Send to WhatsApp Business" }
        assertEquals("com.whatsapp", Shadows.shadowOf(w4bAction.actionIntent).savedIntent.`package`)

        // Apps outside every family share back to exactly themselves.
        listOf("org.thoughtcrime.securesms", "com.example.unknown").forEach { source ->
            val n = factory.build(spec("ciao", sourcePackage = source), prefs)
            val action = n.actions!!.first { it.title.toString().startsWith("Send to ") }
            assertEquals(source, Shadows.shadowOf(action.actionIntent).savedIntent.`package`)
        }
    }

    @Test
    fun `medium text fits one page without truncation or counter`() {
        val text = List(45) { "parola" }.joinToString(" ") // 314 chars (7n - 1)
        val n = factory.build(spec(text), prefs)
        assertEquals(text, n.contentViewText())
        assertNull(n.subTextCompat())
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
    }

    @Test
    fun `first page shows Copy Share Next in that order`() {
        val n = factory.build(spec(longText(3)), prefs)
        assertEquals(listOf("Copy", "Send to Telegram", "Next"), n.titles())
    }

    @Test
    fun `middle page drops Share and shows Prev before Next`() {
        val n = factory.build(spec(longText(3), page = 1), prefs)
        assertEquals(listOf("Copy", "Previous", "Next"), n.titles())
    }

    @Test
    fun `last page has Prev and no Next`() {
        val n = factory.build(spec(longText(3), page = 2), prefs)
        assertEquals(listOf("Copy", "Send to Telegram", "Previous"), n.titles())
    }

    @Test
    fun `copiedToClipboard rides the subText line (TASK-385)`() {
        val n = factory.build(spec("ciao come stai").copy(copiedToClipboard = true), prefs)
        assertEquals("Copied to clipboard", n.subTextCompat())
    }

    @Test
    fun `paged subtext shows page counter`() {
        val n = factory.build(spec(longText(3), page = 1), prefs)
        assertEquals("Page 2 of 3", n.subTextCompat())
    }

    @Test
    fun `copy action carries the full text even mid paging`() {
        val text = longText(3)
        val n = factory.build(spec(text, page = 1), prefs)
        val intent = Shadows.shadowOf(n.actions!!.first { it.title == "Copy" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION, intent.action)
        assertEquals(text, intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
    }

    @Test
    fun `nav intent carries full text page and notification id`() {
        val text = longText(2)
        val n = factory.build(spec(text), prefs)
        val intent = Shadows.shadowOf(n.actions!!.first { it.title == "Next" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_PAGE_NEXT, intent.action)
        assertEquals(text, intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
        assertEquals(0, intent.getIntExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, -1))
        assertEquals(5_000, intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1))
        assertEquals(1_000L, intent.getLongExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, -1L))
    }

    @Test
    fun `prev intent carries full text and page index`() {
        val text = longText(3)
        val n = factory.build(spec(text, page = 1), prefs)
        val intent = Shadows.shadowOf(n.actions!!.first { it.title == "Previous" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_PAGE_PREV, intent.action)
        assertEquals(text, intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
        assertEquals(1, intent.getIntExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, -1))
        assertEquals(5_000, intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1))
        assertEquals(1_000L, intent.getLongExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, -1L))
    }

    @Test
    fun `oversized text falls back to truncated preview and char counter`() {
        val text = List(9_000) { "parola" }.joinToString(" ")
        val n = factory.build(spec(text), prefs)
        assertTrue(n.contentViewText()!!.endsWith("…"))
        assertTrue(n.subTextCompat()!!.startsWith("100 of"))
        assertEquals(listOf("Copy", "Send to Telegram"), n.titles())
    }

    @Test
    fun `repost never re-alerts and keeps firstPostedAt`() {
        val n = factory.build(spec(longText(2), page = 1, repost = true), prefs)
        assertTrue(n.flags.toInt() and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1_000L, n.`when`)
    }

    @Test
    fun `paged subtext shows language and low confidence`() {
        // Native ICU display name for "it" (util/LanguageNames), independent of
        // the app locale. detected_language format: "Detected: %1$s"
        // confidence_low: "Low confidence"
        val n = factory.build(langSpec(longText(3), page = 1), prefs)
        assertEquals("Page 2 of 3 · Detected: Italiano · Low confidence", n.subTextCompat())
    }
}
