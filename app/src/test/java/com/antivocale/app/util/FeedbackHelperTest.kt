package com.antivocale.app.util

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val labels = FeedbackHelper.BodyLabels(
        version = "App version",
        android = "Android",
        device = "Device",
        locale = "Language",
        model = "Active model",
        yourMessage = "Your message:",
        note = "You can remove the technical details above if you prefer."
    )

    private val diagnostics = FeedbackHelper.Diagnostics(
        versionName = "1.10.0",
        versionCode = 27L,
        androidVersion = "16 (API 36)",
        device = "realme RMX3853",
        locale = "it-IT",
        activeBackendId = "parakeet_tdt",
        activeModelName = "Parakeet TDT 0.6B v2"
    )

    // --- Subjects ---

    @Test
    fun `feedback subject uses the agreed prefix`() {
        assertEquals("[Anti-Vocale feedback]", FeedbackHelper.feedbackSubject())
    }

    @Test
    fun `translation subject embeds the current locale`() {
        assertEquals("[Anti-Vocale translation] it-IT", FeedbackHelper.translationSubject("it-IT"))
    }

    // --- Body templates ---

    @Test
    fun `feedback body contains version device locale and active model`() {
        val body = FeedbackHelper.buildFeedbackBody(diagnostics, labels)
        assertTrue(body.contains("1.10.0"))
        assertTrue(body.contains("27"))
        assertTrue(body.contains("16 (API 36)"))
        assertTrue(body.contains("realme RMX3853"))
        assertTrue(body.contains("it-IT"))
        assertTrue(body.contains("parakeet_tdt"))
        assertTrue(body.contains("Parakeet TDT 0.6B v2"))
    }

    @Test
    fun `feedback body has the message section and deletable-diagnostics note`() {
        val body = FeedbackHelper.buildFeedbackBody(diagnostics, labels)
        assertTrue(body.contains("Your message:"))
        assertTrue(body.contains("You can remove the technical details above if you prefer."))
        // The message placeholder must come after the diagnostics block.
        assertTrue(body.indexOf("Your message:") > body.indexOf("1.10.0"))
    }

    @Test
    fun `translation body embeds locale and skips the diagnostics dump`() {
        val body = FeedbackHelper.buildTranslationBody(diagnostics, labels)
        assertTrue(body.contains("it-IT"))
        assertTrue(body.contains("Your message:"))
        assertFalse(body.contains("realme RMX3853"))
        assertFalse(body.contains("1.10.0"))
    }

    // --- Intent factory ---

    @Test
    fun `email intent uses ACTION_SENDTO with mailto uri and extras`() {
        val intent = FeedbackHelper.createEmailIntent("subj", "body")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(
            "mailto:paolo.antinori@risorseartificiali.com?subject=subj&body=body",
            intent.data.toString()
        )
        assertEquals("subj", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("body", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `email intent uri-encodes subject and body query params`() {
        // TASK-374 device finding: current Gmail drops SENDTO extras but honors
        // the URI form, so the query params must survive encoding.
        val intent = FeedbackHelper.createEmailIntent(
            "[Anti-Vocale] task 42 & more",
            "line one\nline two: 100% \"quoted\""
        )
        val uri = intent.data!!.toString()
        assertFalse(uri.contains(' '))
        assertFalse(uri.contains('\n'))
        assertFalse(uri.contains("100% \""))
        assertTrue(uri.startsWith("mailto:paolo.antinori@risorseartificiali.com?subject="))
    }

    // --- Fallback path ---

    @Test
    fun `mailto intent is not callable when no mail app is installed`() {
        val intent = FeedbackHelper.createEmailIntent("s", "b")
        // Robolectric resolves no mail activity by default.
        assertNull(intent.resolveActivity(context.packageManager))
        assertFalse(FeedbackHelper.isCallable(context, intent))
    }

    @Test
    fun `sendOrCopy falls back to clipboard when no mail app handles the intent`() {
        val handled = FeedbackHelper.sendOrCopy(context, "s", "b")
        assertFalse(handled)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            FeedbackHelper.FEEDBACK_ADDRESS,
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
    }

    @Test
    fun `transcript feedback body carries task facts and truncated excerpt`() {
        val facts = FeedbackHelper.TranscriptFacts(
            taskId = "task-42", modelName = "Gemma (LiteRT-LM)",
            audioDurationSeconds = 95.5, processingTimeMs = 12_345,
            status = "SUCCESS",
            excerpt = "a".repeat(400))
        val labels = FeedbackHelper.TranscriptLabels(
            task = "Task", model = "Model", duration = "Audio length",
            time = "Processing time", status = "Status", excerpt = "Transcript excerpt",
            truncatedNote = "truncated")
        val body = FeedbackHelper.buildTranscriptFeedbackBody(facts, labels)
        org.junit.Assert.assertTrue(body.contains("task-42"))
        org.junit.Assert.assertTrue(body.contains("Gemma (LiteRT-LM)"))
        org.junit.Assert.assertTrue(body.contains("95.5"))
        org.junit.Assert.assertTrue(body.contains("12.3"))  // seconds rendering
        org.junit.Assert.assertTrue(body.contains("SUCCESS"))
        // No errorMessage -> no extra line
        // Excerpt capped at 300 chars + marker
        org.junit.Assert.assertFalse(body.contains("a".repeat(301)))
        org.junit.Assert.assertTrue(body.contains("a".repeat(300)))
        org.junit.Assert.assertTrue(body.contains(labels.truncatedNote))
    }

    @Test
    fun `transcript feedback body includes the error message when present`() {
        val facts = FeedbackHelper.TranscriptFacts(
            taskId = "t", modelName = "m", audioDurationSeconds = 1.0,
            processingTimeMs = 100L, status = "ERROR", excerpt = "",
            errorMessage = "NativeError: onnx runtime failure")
        val labels = FeedbackHelper.TranscriptLabels(
            task = "Task", model = "Model", duration = "D", time = "T",
            status = "S", excerpt = "Excerpt", truncatedNote = "truncated")
        val body = FeedbackHelper.buildTranscriptFeedbackBody(facts, labels)
        org.junit.Assert.assertTrue(body.contains("onnx runtime failure"))
    }

    @Test
    fun `transcript feedback body handles empty excerpt`() {
        val facts = FeedbackHelper.TranscriptFacts(
            taskId = "t", modelName = "m", audioDurationSeconds = 1.0,
            processingTimeMs = 100L, status = "ERROR", excerpt = "")
        val labels = FeedbackHelper.TranscriptLabels(
            task = "Task", model = "Model", duration = "D", time = "T",
            status = "S", excerpt = "Excerpt", truncatedNote = "truncated")
        val body = FeedbackHelper.buildTranscriptFeedbackBody(facts, labels)
        org.junit.Assert.assertTrue(body.isNotEmpty())
        org.junit.Assert.assertFalse(body.contains("truncated"))
    }

    @Test
    fun `transcript feedback body carries the tail and processing attribution`() {
        // TASK-512: long excerpts gain the last-100-chars tail (truncation vs
        // repetition-loop is instantly readable) and the env/processing line.
        val longExcerpt = "a".repeat(FeedbackHelper.TRANSCRIPT_EXCERPT_CAP + 50) +
            "THE-TAIL-MARKER"
        val facts = FeedbackHelper.TranscriptFacts(
            taskId = "t", modelName = "m", audioDurationSeconds = 1.0,
            processingTimeMs = 100L, status = "SUCCESS", excerpt = longExcerpt,
            appVersion = "1.13.0-SNAPSHOT", deviceModel = "RMX3853",
            processingLine = "pipeline chunks=157 cap=60s")
        val labels = FeedbackHelper.TranscriptLabels(
            task = "Task", model = "Model", duration = "D", time = "T",
            status = "S", excerpt = "Excerpt", truncatedNote = "truncated")
        val body = FeedbackHelper.buildTranscriptFeedbackBody(facts, labels)
        org.junit.Assert.assertTrue(body.contains("tail: ..."))
        org.junit.Assert.assertTrue(body.substringAfterLast("tail: ...").contains("THE-TAIL-MARKER"))
        org.junit.Assert.assertTrue(body.contains("v1.13.0-SNAPSHOT RMX3853 | pipeline chunks=157 cap=60s"))
    }

    @Test
    fun `transcript feedback body omits the tail and attribution when absent`() {
        val facts = FeedbackHelper.TranscriptFacts(
            taskId = "t", modelName = "m", audioDurationSeconds = 1.0,
            processingTimeMs = 100L, status = "SUCCESS", excerpt = "short")
        val labels = FeedbackHelper.TranscriptLabels(
            task = "Task", model = "Model", duration = "D", time = "T",
            status = "S", excerpt = "Excerpt", truncatedNote = "truncated")
        val body = FeedbackHelper.buildTranscriptFeedbackBody(facts, labels)
        org.junit.Assert.assertFalse(body.contains("tail:"))
        org.junit.Assert.assertFalse(body.contains("|"))
    }

    @Test
    fun `transcript feedback subject groups by task`() {
        org.junit.Assert.assertEquals(
            "[Anti-Vocale feedback] task task-42",
            FeedbackHelper.transcriptFeedbackSubject("task-42"))
    }
}
