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
import com.antivocale.app.receiver.TaskerRequestReceiver
import com.antivocale.app.util.AppInfoUtils
import com.antivocale.app.util.TranscriptSignature
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.ui.AppNavigation
import java.util.concurrent.atomic.AtomicInteger

/** Everything needed to (re)build one result notification (TASK-327). */
data class ResultNotificationSpec(
    val transcriptionText: String,
    /** TASK-647: resolved signature for the EXIT surfaces (copy/share
     *  actions). Blank = feature off (the raw text goes out unchanged);
     *  the body and the nav intents always carry the raw transcript. */
    val signatureText: String = "",
    val signaturePosition: String = "append",
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
    /** TASK-450: the request was streamed without silence stripping after the
     *  VAD path would have refused it (device memory ceiling); said in subText. */
    val streamedWithoutVad: Boolean = false,
    /** GH #43: the fast backend a two-pass run refined (display name), or
     *  the sentinel NOT_REFINED when the first pass shipped unrefined. */
    val refinedFrom: String? = null,
    /** GH #43 sentinel for [refinedFrom]: delivered unrefined (F4/F5). */
    val notRefined: Boolean = false,
    /** TASK-583 (GH #110): the delivered single-model transcript matched the
     *  repetition-loop detector; said in subText, leading it. */
    val repetitionSuspected: Boolean = false,
    /** TASK-722: the auto-save failure reason when the export could not be
     *  written; null when saved, not configured, or the run predates it. */
    val saveFailureReason: String? = null,
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

    fun noModelNotification(): Notification {
        // TASK-328: the no-model notification, ONE builder (was a line-for-line
        // pair in InferenceService and TranscriptionNotificationListener).
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_MODEL_TAB, true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, RC_ERROR_LAUNCH_MODEL_TAB, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(context.getString(R.string.notification_no_model_title))
            .setContentText(context.getString(R.string.notification_no_model_message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_set_as,
                context.getString(R.string.notification_no_model_action),
                openPendingIntent
            )
            .build()
    }

    /**
     * TASK-625: the transcription-failure error notification, shared by both
     * error surfaces (InferenceService and TranscriptionNotificationListener)
     * and by the debug TEST_SPI simulate op. With [memoryAction] the content
     * intent and an action button deep-link to the memory-protection settings
     * row; the request codes mirror the services' launch band (same intent
     * shapes must stay one PendingIntent).
     */
    fun errorNotification(errorMessage: String, memoryAction: Boolean): Notification {
        val launch = if (memoryAction) {
            settingsRowPendingIntent(AppNavigation.ROW_KEY_MEMORY_PROTECTION)
        } else {
            plainLaunchPendingIntent()
        }
        val builder = NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(context.getString(R.string.transcription_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(launch)
            .setAutoCancel(true)
        if (memoryAction) {
            builder.addAction(
                android.R.drawable.ic_menu_set_as,
                context.getString(R.string.error_open_memory_protection_setting),
                launch
            )
        }
        return builder.build()
    }

    /**
     * TASK-640: a plain alert on the result channel (title, text,
     * app-launch content intent). The quarantine notice and any future
     * one-shot alerts compose here instead of hand-rolling builders outside
     * the service layer; [priority] and [channel] default to the
     * high-importance result channel, the quiet-summary variants override
     * both.
     */
    fun alertNotification(
        title: String,
        text: String,
        priority: Int = NotificationCompat.PRIORITY_HIGH,
        channel: AppNotificationChannel = AppNotificationChannel.TRANSCRIPTION_RESULT,
    ): Notification =
        NotificationCompat.Builder(context, channel.id)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(priority)
            .setContentIntent(plainLaunchPendingIntent())
            .setAutoCancel(true)
            .build()

    /**
     * TASK-684 (GH #109): the quiet summary for the GENERIC interrupted
     * class (rows closed by the sweep that the classifier could not name a
     * cause for). No retry action (unlike the suspended class there is no
     * proven file to re-run), no battery link: this is the honest "your run
     * did not finish" the reporter was missing, one notification for the
     * whole batch, count and History pointer in BOTH arms. Its own
     * IMPORTANCE_DEFAULT channel: a step quieter than the proven
     * suspension's heads-up, and independently toggleable at the system
     * level. Channel created here so every post site is covered (the
     * lazy-create pattern the other channels use).
     */
    fun interruptedRunsNotification(count: Int, oom: Boolean): Notification {
        AppNotificationChannel.INTERRUPTED_RUNS.create(context)
        val textRes = if (oom) R.plurals.interrupted_runs_oom_text else R.plurals.interrupted_runs_text
        return alertNotification(
            title = context.getString(R.string.interrupted_runs_title),
            text = context.resources.getQuantityString(textRes, count, count),
            priority = NotificationCompat.PRIORITY_DEFAULT,
            channel = AppNotificationChannel.INTERRUPTED_RUNS,
        )
    }

    /**
     * TASK-684 (GH #109): the OEM-freezer suspension outcome. The honest
     * message (already localized, duration included) plus the two one-tap
     * remedies: re-run the same audio (a broadcast the
     * [NotificationActionReceiver] re-enqueues through [InferenceEnqueue])
     * and the battery-exemption deep link (the 1.11-prep guidance, the same
     * system dialog the Settings card opens). Two actions, inside the
     * three-button shade cap.
     */
    fun suspensionNotification(
        text: String,
        rerunTaskId: String,
        filePath: String?,
        prompt: String?,
        sourcePackage: String?,
        retryFileAlive: Boolean = true,
        /** TASK-736: the original row's sender, so the re-run's row keeps the label. */
        senderName: String? = null,
    ): Notification {
        val rerunIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_RERUN_SUSPENDED
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, filePath)
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, prompt ?: "")
            sourcePackage?.let { putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, it) }
            senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
            // Diagnostic only: the re-run mints its own task id.
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, rerunTaskId)
        }
        val rerun = PendingIntent.getBroadcast(
            context, RC_SUSPENSION_RERUN, rerunIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val battery = PendingIntent.getActivity(
            context, RC_SUSPENSION_BATTERY,
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + context.packageName)
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(context.getString(R.string.suspension_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(plainLaunchPendingIntent())
            .setAutoCancel(true)
            // TASK-684 review: the Retry action only when the source file
            // survived (shared_audio's 24h cleanup runs before this sweep;
            // a doomed Retry would toast "file not found" on every tap).
            .apply {
                if (retryFileAlive) {
                    addAction(
                        android.R.drawable.ic_media_play,
                        context.getString(R.string.retranscribe),
                        rerun
                    )
                }
            }
            .addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.battery_exemption_action),
                battery
            )
            .build()
    }

    /** The plain app launch both error surfaces default to. */
    private fun plainLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context, RC_ERROR_LAUNCH_DEFAULT,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * TASK-625: the settings-row deep link. In-app handoff (the live activity
     * receives the extra via onNewIntent), not a task clear.
     */
    fun settingsRowPendingIntent(rowKey: String): PendingIntent = PendingIntent.getActivity(
        context, RC_ERROR_LAUNCH_SETTINGS_ROW,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_NAVIGATE_TO_SETTINGS_ROW, rowKey),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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
                copyPendingIntent(text, spec)
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
        if (spec.repetitionSuspected) {
            subTextParts.add(context.getString(R.string.warning_repetition_suspected))
        }

        if (spec.saveFailureReason != null) {
            // TASK-722: a failed auto-save is never silent; the reason token
            // rides the subtext AFTER the repetition warning (TASK-583: the
            // warning that the text may be garbage leads the status facts).
            subTextParts.add(
                context.getString(R.string.auto_save_failed, spec.saveFailureReason))
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
        if (spec.streamedWithoutVad) {
            subTextParts.add(context.getString(R.string.transcription_streamed_without_vad))
        }
        when {
            spec.notRefined -> subTextParts.add(context.getString(R.string.transcription_not_refined))
            spec.refinedFrom != null -> subTextParts.add(
                context.getString(R.string.transcription_refined_from, spec.refinedFrom))
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
                putExtra(Intent.EXTRA_TEXT, TranscriptSignature.apply(spec.transcriptionText, spec.signatureText, spec.signaturePosition))
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
                putExtra(Intent.EXTRA_TEXT, TranscriptSignature.apply(spec.transcriptionText, spec.signatureText, spec.signaturePosition))
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

    private fun copyPendingIntent(text: String, spec: ResultNotificationSpec): PendingIntent {
        val signed = TranscriptSignature.apply(
            text, spec.signatureText, spec.signaturePosition)
        val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_TRANSCRIPTION
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, signed)
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
            // TASK-647: the rebuilt notification's copy/share actions must
            // keep signing; the body stays raw.
            putExtra(NotificationActionReceiver.EXTRA_SIGNATURE_TEXT, spec.signatureText)
            putExtra(NotificationActionReceiver.EXTRA_SIGNATURE_POSITION, spec.signaturePosition)
            putExtra(NotificationActionReceiver.EXTRA_PAGE_INDEX, pageIndex)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, spec.notificationId)
            putExtra(NotificationActionReceiver.EXTRA_FIRST_POSTED_AT, spec.firstPostedAt)
            putExtra(NotificationActionReceiver.EXTRA_SAVE_FAILURE, spec.saveFailureReason)
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
        // Mirror the services' launch-band constants so the same intent shape
        // stays a single PendingIntent whichever builder produced it.
        private const val RC_ERROR_LAUNCH_DEFAULT = 0
        private const val RC_ERROR_LAUNCH_MODEL_TAB = 1
        private const val RC_ERROR_LAUNCH_SETTINGS_ROW = 2

        // TASK-684: the suspension notification's action band. Fixed codes:
        // one live suspension notification at a time (fixed id), and
        // FLAG_UPDATE_CURRENT replaces its intents on re-post.
        private const val RC_SUSPENSION_RERUN = 3
        private const val RC_SUSPENSION_BATTERY = 4

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
         * - 1005: CrashQuarantineCheck.NOTIFICATION_ID (TASK-640 quarantine notice)
         * - 1006: TranscriptionOrchestrator.MEMORY_MARGIN_WARNING_ID (TASK-631
         *   part-2 dismissable tight-margin warning, default path)
         * - 1007: MemoryKillStartupCheck.NOTIFICATION_ID (TASK-426 previous
         *   process killed by memory enforcement, once per kill)
         * - 1009: ModelShortcutActivity.SWITCH_NOTIFICATION_ID (TASK-552 the
         *   model-switch confirmation, self-replacing)
         * - 2001..2100: ExtractionService download-progress band (per-jobKey hash)
         * - 2201..2300: TaskerRequestReceiver fallback band (sequential slots)
         * - 2401..2500: ShareReceiverActivity choice + share-error band
         *   (per-taskId / per-message hash, TASK-440)
         * - 2501: LogsViewModel.HISTORY_ERROR_NOTIFICATION_ID (F1 History
         *   error surface, TASK-500 F-batch)
         * - 2502: SuspendedRunRecovery.NOTIFICATION_ID (TASK-684 freezer
         *   suspension outcome)
         * - 2503: SuspendedRunRecovery.INTERRUPTED_NOTIFICATION_ID (TASK-684
         *   generic interrupted-runs summary)
         * New fixed ids or bands go under the base; 2301..2400 and 2504..2999
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
