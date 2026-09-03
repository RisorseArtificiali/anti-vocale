package com.antivocale.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.antivocale.app.MainActivity
import com.antivocale.app.R
import com.antivocale.app.data.AppNotificationPreferences
import com.antivocale.app.receiver.NotificationActionReceiver
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.LanguageNames
import java.util.concurrent.atomic.AtomicInteger

/** Everything needed to (re)build one result notification (TASK-327). */
data class ResultNotificationSpec(
    val transcriptionText: String,
    val taskId: String?,
    val sourcePackage: String?,
    val confidence: Float?,
    val detectedLanguage: String?,
    val isPartial: Boolean = false,
    val failedChunkCount: Int = 0,
    val pageIndex: Int = 0,
    val notificationId: Int,
    /** TASK-385: the clipboard was silently modified; surfaced in subText instead of a toast-only signal. */
    val copiedToClipboard: Boolean = false,
    val firstPostedAt: Long = System.currentTimeMillis(),
    /** True when rebuilding after a prev/next tap: suppresses re-alerting. */
    val repost: Boolean = false
)

/**
 * The single builder for completed-transcription result notifications
 * (TASK-327). Extracted from the two previously duplicated
 * showResultNotification implementations (InferenceService and
 * TranscriptionNotificationListener); both now delegate here.
 *
 * Synchronous by design: callers fetch [AppNotificationPreferences] (a suspend
 * DataStore read) on their own scheduler and pass the value in, so this class
 * stays trivially testable.
 *
 * Also owns the process-wide notification-id allocator: every post in both
 * delegating classes (result, error, no-model) draws from [nextNotificationId],
 * replacing the two per-class counters that both seeded at 1002 and could
 * collide. Ids are unique within a process lifetime only; after process death
 * the sequence restarts at [RESULT_NOTIFICATION_ID_BASE] (TASK-329). The
 * companion also hosts [bandedNotificationId], the derivation every per-key
 * producer below the base uses to stay out of the allocator's range.
 */
class ResultNotificationFactory(private val context: Context) {

    init {
        // Idempotent; the receiver path can run in a fresh process where no
        // service ever created the channel.
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(context)
    }

    fun build(spec: ResultNotificationSpec, prefs: AppNotificationPreferences): Notification {
        val text = spec.transcriptionText
        // One split pass: skip it entirely for unpageable oversized texts.
        val oversized = text.length > TranscriptPager.MAX_PAGED_LENGTH
        val pages = if (oversized) listOf(text) else TranscriptPager.pagesFor(text)
        val paged = !oversized && pages.size >= 2
        val pageIndex = spec.pageIndex.coerceIn(0, pages.size - 1)

        val title = if (spec.isPartial) {
            context.resources.getQuantityString(R.plurals.transcription_partial, spec.failedChunkCount, spec.failedChunkCount)
        } else {
            context.getString(R.string.transcription_complete)
        }

        // Body text: the current page when paged, the whole text otherwise.
        // Legacy truncation applies only to texts too big to page (binder
        // guard): everything pageable is fully readable, single page or paged.
        val displayed = if (paged) pages[pageIndex] else text
        val contentText = if (!paged && oversized) text.take(CHAR_PREVIEW_LIMIT) + "…" else displayed

        val builder = NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayed))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(buildLaunchPendingIntent(spec.taskId))
            .setWhen(spec.firstPostedAt)
            .setOnlyAlertOnce(spec.repost)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_save,
                context.getString(R.string.copy),
                copyPendingIntent(text)
            )

        // On-device finding (TASK-327 Task 8, Realme RMX3853 / Android 16): the shade
        // renders at most three action buttons, collapsed AND expanded. On middle
        // pages both nav arrows must stay visible for bidirectional paging, so Share
        // is the action that gives way there; it returns on first/last pages and on
        // unpaged notifications.
        val middlePage = paged && pageIndex > 0 && pageIndex < pages.size - 1
        if (prefs.showShareAction && !middlePage) {
            addShareAction(builder, spec, prefs)
        }

        // Nav actions mirror the in-progress notification's structure (user
        // decision): fixed anchors first, nav after, progressive disclosure,
        // and Prev before Next so a middle page reads Copy, Previous, Next. // TASK-377
        if (paged && pageIndex > 0) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                context.getString(R.string.chunk_nav_prev),
                navPendingIntent(spec, pageIndex, isPrev = true)
            )
        }
        if (paged && pageIndex < pages.size - 1) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                context.getString(R.string.chunk_nav_next),
                navPendingIntent(spec, pageIndex, isPrev = false)
            )
        }

        val subTextParts = mutableListOf<String>()
        when {
            paged -> subTextParts.add(
                context.getString(R.string.page_counter, pageIndex + 1, pages.size)
            )
            oversized -> subTextParts.add(
                context.getString(R.string.char_counter, CHAR_PREVIEW_LIMIT, text.length)
            )
        }
        val langLabel = spec.detectedLanguage?.let { lang ->
            LanguageNames.nativeLanguageName(lang)
        }
        if (langLabel != null) {
            subTextParts.add(context.getString(R.string.detected_language, langLabel))
        }
        if (spec.confidence != null && spec.confidence < CONFIDENCE_MEDIUM_THRESHOLD) {
            subTextParts.add(context.getString(R.string.confidence_low))
        }
        if (spec.copiedToClipboard) {
            subTextParts.add(context.getString(R.string.copied_to_clipboard))
        }
        if (subTextParts.isNotEmpty()) {
            builder.setSubText(subTextParts.joinToString(" · "))
        }

        return builder.build()
    }

    private fun addShareAction(
        builder: NotificationCompat.Builder,
        spec: ResultNotificationSpec,
        prefs: AppNotificationPreferences
    ) {
        val useQuickShareBack = prefs.quickShareBack && spec.sourcePackage != null
        if (useQuickShareBack) {
            val shareBackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, spec.transcriptionText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Family normalization (forks, flavor builds) lives in the one
                // known-app table in AppInfoUtils (TASK-433).
                setPackage(AppInfoUtils.shareBackTarget(spec.sourcePackage))
            }
            val shareBackPendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt() + 1,
                shareBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_send,
                AppInfoUtils.getSendToText(context, spec.sourcePackage),
                shareBackPendingIntent
            )
        } else {
            val shareChooserIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, spec.transcriptionText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val sharePickerIntent = Intent.createChooser(
                shareChooserIntent,
                context.getString(R.string.share_transcription)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val sharePendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt() + 1,
                sharePickerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_share,
                context.getString(R.string.share),
                sharePendingIntent
            )
        }
    }

    private fun copyPendingIntent(text: String): PendingIntent {
        val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Nav intents carry everything needed to rebuild the neighbor page. The
     * request code is distinct per (notification, page, direction): PendingIntent
     * equality ignores extras, so shared codes would collapse distinct pages
     * into one cached intent.
     */
    private fun navPendingIntent(spec: ResultNotificationSpec, pageIndex: Int, isPrev: Boolean): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = if (isPrev) {
                NotificationActionReceiver.ACTION_PAGE_PREV
            } else {
                NotificationActionReceiver.ACTION_PAGE_NEXT
            }
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, spec.transcriptionText)
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, pageIndex)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, spec.notificationId)
            putExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, spec.firstPostedAt)
            putExtra(NotificationActionReceiver.EXTRA_IS_PARTIAL, spec.isPartial)
            putExtra(NotificationActionReceiver.EXTRA_FAILED_CHUNK_COUNT, spec.failedChunkCount)
            spec.taskId?.let { putExtra(NotificationActionReceiver.EXTRA_TASK_ID, it) }
            spec.sourcePackage?.let { putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, it) }
            spec.confidence?.let { putExtra(NotificationActionReceiver.EXTRA_CONFIDENCE, it) }
            spec.detectedLanguage?.let { putExtra(NotificationActionReceiver.EXTRA_DETECTED_LANGUAGE, it) }
        }
        val requestCode = spec.notificationId * 1000 + pageIndex * 2 + if (isPrev) 0 else 1
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildLaunchPendingIntent(highlightTaskId: String?): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            if (highlightTaskId != null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_HIGHLIGHT_TASK_ID, highlightTaskId)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
        return PendingIntent.getActivity(
            context,
            highlightTaskId?.hashCode() ?: 0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Preview truncation for the non-pageable oversized path, unchanged from the previous implementations. */
        const val CHAR_PREVIEW_LIMIT = 100

        private const val CONFIDENCE_MEDIUM_THRESHOLD = 0.5f

        /**
         * Reserved-range contract (TASK-329): the allocator owns every id at or
         * above this base, and every fixed or banded notification id elsewhere
         * must stay below it, so an allocator id can never replace another
         * notification or be replaced by one. Occupants of the fixed range
         * today, all below 3000:
         * - 1001: InferenceService.NOTIFICATION_ID (service foreground/progress)
         * - 1003: SubtitleChoiceTimeoutWorker.NOTIFICATION_ID (worker foreground)
         * - 2001..2100: ExtractionService download-progress band (per-jobKey hash)
         * - 2201..2300: TaskerRequestReceiver fallback band (per-taskId hash)
         * - 2401..2500: ShareReceiverActivity choice + share-error band
         *   (per-taskId / per-message hash, TASK-440)
         * New fixed ids or bands go under the base; 2301..2400 and 2501..2999
         * are free headroom.
         */
        const val RESULT_NOTIFICATION_ID_BASE = 3000

        /**
         * Shared derivation for per-key notification ids: folds an arbitrary
         * hash into a reserved band [base, base + range - 1], the idiom behind
         * every banded id in the contract table above. The mask is load
         * bearing and NOT interchangeable with abs(): abs(Int.MIN_VALUE) is
         * still Int.MIN_VALUE, so an abs-based variant emits ids below the
         * band for negative hashes and breaks the contract, while
         * (hash and 0x7FFFFFFF) is in 0..0x7FFFFFFF for any Int, keeping the
         * result inside the band.
         */
        internal fun bandedNotificationId(hash: Int, base: Int, range: Int): Int =
            base + (hash and 0x7FFFFFFF) % range

        /** Process-wide id allocator; the seed doubles as the first id of a fresh process. */
        private val idCounter = AtomicInteger(RESULT_NOTIFICATION_ID_BASE)

        fun nextNotificationId(): Int = idCounter.getAndIncrement()
    }
}
