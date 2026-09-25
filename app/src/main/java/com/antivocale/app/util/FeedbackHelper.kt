package com.antivocale.app.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.antivocale.app.R
import java.util.Locale

/**
 * Builds the feedback email (issue #34 / TASK-341): subject/body templates from
 * device diagnostics, an ACTION_SENDTO intent factory, and a copy-to-clipboard
 * fallback when no mail app can handle the intent. Pure template functions so
 * the email content is unit-testable without a device.
 */
object FeedbackHelper {

    const val FEEDBACK_ADDRESS = "paolo.antinori@risorseartificiali.com"
    const val SOURCE_CODE_URL = "https://github.com/RisorseArtificiali/anti-vocale"

    private const val SUBJECT_FEEDBACK = "[Anti-Vocale feedback]"
    private const val SUBJECT_TRANSLATION_PREFIX = "[Anti-Vocale translation]"

    /** Device/app facts embedded in the feedback body. */
    data class Diagnostics(
        val versionName: String,
        val versionCode: Long,
        val androidVersion: String,
        val device: String,
        val locale: String,
        val activeBackendId: String?,
        val activeModelName: String?
    )

    /** Localized labels for the body template (wired from string resources). */
    data class BodyLabels(
        val version: String,
        val android: String,
        val device: String,
        val locale: String,
        val model: String,
        val yourMessage: String,
        val note: String
    )

    fun feedbackSubject(): String = SUBJECT_FEEDBACK

    /**
     * TASK-374: facts about one transcription, embedded in a per-entry feedback
     * email. The excerpt is TRUNCATED by the builder (privacy: the user reviews
     * and edits the email before sending; full transcripts are never attached).
     */
    data class TranscriptFacts(
        val taskId: String,
        val modelName: String,
        val audioDurationSeconds: Double,
        val processingTimeMs: Long,
        val status: String,
        val excerpt: String,
        /** For ERROR reports: the recorded failure reason, often the most useful fact. */
        val errorMessage: String? = null,
        /** TASK-570: rendered structured diagnostics (backend/provider/version/
         *  chunks/durations) when the failure wrote them. */
        val failureDiagnostics: String? = null,
        /** TASK-512: app version and device model, so the email stands alone. */
        val appVersion: String? = null,
        val deviceModel: String? = null,
        /** TASK-512: rendered processing context (decode path, chunks, cap, RAM). */
        val processingLine: String? = null,
        /** TASK-511: the row is a partial delivery (some chunks failed). */
        val isPartial: Boolean = false,
        /** TASK-511: how many chunks decoded blank/failed. */
        val failedChunkCount: Int = 0,
    )

    /** Localized labels for the per-transcription body template. */
    data class TranscriptLabels(
        val task: String,
        val model: String,
        val duration: String,
        val time: String,
        val status: String,
        val excerpt: String,
        val truncatedNote: String,
    )

    /** Cap for the excerpt embedded in the body; referenced by the tests too. */
    const val TRANSCRIPT_EXCERPT_CAP = 300

    /** TASK-512: chars of the result TAIL embedded beside the head excerpt. */
    const val TRANSCRIPT_TAIL_CHARS = 100

    fun transcriptFeedbackSubject(taskId: String) = "$SUBJECT_FEEDBACK task $taskId"

    /** TASK-512: one version-name read for the diagnostics email, the
     *  orchestrator's failure context, and the transcript report. */
    fun currentVersionName(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    fun buildTranscriptFeedbackBody(f: TranscriptFacts, l: TranscriptLabels): String = buildString {
        appendLine("${l.task}: ${f.taskId}")
        appendLine("${l.model}: ${f.modelName}")
        appendLine("${l.duration}: ${"%.1f".format(f.audioDurationSeconds)}s")
        // TASK-568: 0 means "not applicable" (ERROR rows carry decoded
        // audio in the column, not processing time), not a zero-length run.
        if (f.processingTimeMs > 0) {
            appendLine("${l.time}: ${"%.1f".format(f.processingTimeMs / 1000.0)}s")
        }
        // One status line: the error rides in parentheses instead of
        // repeating the label on a second line (which read as a duplicate).
        val errorSuffix = f.errorMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        appendLine("${l.status}: ${f.status}$errorSuffix")
        if (f.isPartial) {
            appendLine("(${f.failedChunkCount} chunk(s) failed; transcript is partial)")
        }
        f.failureDiagnostics?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        // TASK-512: standalone attribution + the tail that instantly tells
        // truncation from a repetition loop (the head alone cannot).
        buildList {
            val env = listOfNotNull(f.appVersion?.let { "v$it" }, f.deviceModel)
            if (env.isNotEmpty()) add(env.joinToString(" "))
            f.processingLine?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" | ")) }
        if (f.excerpt.length > TRANSCRIPT_EXCERPT_CAP) {
            appendLine("tail: ...${f.excerpt.takeLast(TRANSCRIPT_TAIL_CHARS)}")
        }
        appendLine()
        append("${l.excerpt}: ")
        if (f.excerpt.isEmpty()) {
            appendLine("(empty)")
        } else if (f.excerpt.length <= TRANSCRIPT_EXCERPT_CAP) {
            appendLine(f.excerpt)
        } else {
            appendLine(f.excerpt.take(TRANSCRIPT_EXCERPT_CAP) + "... (${l.truncatedNote})")
        }
    }

    fun translationSubject(locale: String): String = "$SUBJECT_TRANSLATION_PREFIX $locale"

    fun buildFeedbackBody(d: Diagnostics, l: BodyLabels): String = buildString {
        appendLine("${l.version}: ${d.versionName} (${d.versionCode})")
        appendLine("${l.android}: ${d.androidVersion}")
        appendLine("${l.device}: ${d.device}")
        appendLine("${l.locale}: ${d.locale}")
        appendLine("${l.model}: ${d.activeBackendId ?: "-"} / ${d.activeModelName ?: "-"}")
        appendLine()
        appendLine(l.yourMessage)
        appendLine()
        appendLine(l.note)
    }

    /** Shorter variant for wrong-translation reports: locale only, no device dump. */
    fun buildTranslationBody(d: Diagnostics, l: BodyLabels): String = buildString {
        appendLine("${l.locale}: ${d.locale}")
        appendLine()
        appendLine(l.yourMessage)
        appendLine()
        appendLine(l.note)
    }

    /**
     * Subject/body travel BOTH in the mailto URI query params and as intent
     * extras: current Gmail builds (verified on-device 2026-08-24) silently
     * drop EXTRA_SUBJECT/EXTRA_TEXT on ACTION_SENDTO but honor the URI form,
     * while other clients rely on the extras.
     */
    fun createEmailIntent(subject: String, body: String): Intent {
        val encodedSubject = Uri.encode(subject)
        val encodedBody = Uri.encode(body)
        val uri = Uri.parse(
            "mailto:$FEEDBACK_ADDRESS?subject=$encodedSubject&body=$encodedBody"
        )
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }

    fun isCallable(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /**
     * Launches the mail app, or falls back to copying the address to the
     * clipboard with a toast. Returns true when the mail intent was started.
     */
    fun sendOrCopy(context: Context, subject: String, body: String): Boolean {
        val intent = createEmailIntent(subject, body)
        if (!isCallable(context, intent)) {
            copyAddressToClipboard(context)
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            copyAddressToClipboard(context)
            false
        }
    }

    private fun copyAddressToClipboard(context: Context) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("email", FEEDBACK_ADDRESS))
        Toast.makeText(
            context,
            context.getString(R.string.settings_feedback_address_copied, FEEDBACK_ADDRESS),
            Toast.LENGTH_LONG
        ).show()
    }

    /** Gathers the diagnostics from the running app; the model fields come from the ViewModel.
     *  [localeTag] lets the caller report the IN-APP language rather than the system locale. */
    fun currentDiagnostics(
        context: Context,
        activeBackendId: String?,
        activeModelName: String?,
        localeTag: String = Locale.getDefault().toLanguageTag(),
    ): Diagnostics {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return Diagnostics(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            locale = localeTag,
            activeBackendId = activeBackendId,
            activeModelName = activeModelName
        )
    }
}
