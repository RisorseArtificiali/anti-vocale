package com.antivocale.app.service

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.receiver.TaskerRequestReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the shared result-notification builder (TASK-327);
 * layout and actions only. The reserved-notification-id contract lives in
 * ReservedNotificationIdContractTest (split when the second band landed).
 */
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
        signatureText: String = "",
        signaturePosition: String = "append",
        page: Int = 0,
        repost: Boolean = false,
        sourcePackage: String = "org.telegram.messenger"
    ) = ResultNotificationSpec(
        transcriptionText = text,
        signatureText = signatureText,
        signaturePosition = signaturePosition,
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
    fun `an auto-save failure rides the subText, after the repetition warning (TASK-722)`() {
        val n = factory.build(
            spec("ciao come stai").copy(saveFailureReason = "create_refused"), prefs)
        assertEquals("Auto-save failed (create_refused)", n.subTextCompat())
        // TASK-583 order contract: when both facts are present the repetition
        // warning leads, the save failure follows.
        val both = factory.build(
            spec(longText(3), page = 1).copy(
                repetitionSuspected = true, saveFailureReason = "not_writable"), prefs)
        val sub = requireNotNull(both.subTextCompat())
        assertTrue(sub.indexOf("Repetition") < sub.indexOf("Auto-save failed"))
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
    /** TASK-647: the copy and share actions carry the signed text; the notification BODY stays raw. */
    @Test
    fun `signature signs the copy action while the body stays raw`() {
        val n = factory.build(
            spec("ciao", signatureText = "-- AI --", signaturePosition = "append"), prefs)
        assertEquals("ciao", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val copyAction = n.actions.first { it.actionIntent != null }
        val saved = org.robolectric.Shadows.shadowOf(copyAction.actionIntent).savedIntent
        assertEquals(
            "ciao\n-- AI --",
            saved.getStringExtra(com.antivocale.app.receiver.NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
    }

    @Test
    fun `blank signature leaves the copy action raw`() {
        val n = factory.build(spec("ciao"), prefs)
        assertEquals("ciao", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val copyAction = n.actions.first { it.actionIntent != null }
        val saved = org.robolectric.Shadows.shadowOf(copyAction.actionIntent).savedIntent
        assertEquals(
            "ciao",
            saved.getStringExtra(com.antivocale.app.receiver.NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT))
    }

    /**
     * TASK-684 (GH #109): the suspension outcome notification. Retry and the
     * battery deep link, inside the three-button shade cap; the re-run
     * broadcast carries the full re-enqueue payload.
     */
    @Test
    fun `suspension notification carries retry and battery actions with the rerun payload`() {
        val n = factory.suspensionNotification(
            text = "The system suspended the app for 5m 0s while it was transcribing, so it did not finish.",
            rerunTaskId = "task-9",
            filePath = "/shared_audio/long.wav",
            prompt = "",
            sourcePackage = "org.telegram.messenger")

        assertEquals(listOf("Retry", "Open setting"), n.titles())
        val rerun = Shadows.shadowOf(n.actions!!.first { it.title == "Retry" }.actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_RERUN_SUSPENDED, rerun.action)
        assertEquals("/shared_audio/long.wav", rerun.getStringExtra(TaskerRequestReceiver.EXTRA_FILE_PATH))
        assertEquals("task-9", rerun.getStringExtra(NotificationActionReceiver.EXTRA_TASK_ID))
        // The battery action is the same system dialog the Settings card opens.
        val battery = Shadows.shadowOf(n.actions!!.first { it.title == "Open setting" }.actionIntent).savedIntent
        assertEquals(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
    }

    /**
     * TASK-684: the generic interrupted-runs summary. Quiet by design: its
     * own IMPORTANCE_DEFAULT channel (the suspended class is HIGH on the
     * result channel), no actions, the count in BOTH arms' bodies.
     */
    @Test
    fun `interrupted runs summary is a quiet count notification without actions`() {
        val n = factory.interruptedRunsNotification(count = 2, oom = false)
        assertEquals("Interrupted transcriptions",
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("2"))
        assertNull("no actions: nothing is proven re-runnable", n.actions)
        // The compat builder copies setPriority into the (deprecated but
        // populated) platform field; on O+ the channel carries importance.
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, n.priority)
        assertEquals(com.antivocale.app.util.AppNotificationChannel.INTERRUPTED_RUNS.id, n.channelId)

        // The OOM arm keeps the count and the History pointer, swapping in
        // the memory advice.
        val oom = factory.interruptedRunsNotification(count = 2, oom = true)
        val oomText = oom.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(oomText.contains("2"))
        assertTrue(oomText.contains("out of memory"))
    }
}
