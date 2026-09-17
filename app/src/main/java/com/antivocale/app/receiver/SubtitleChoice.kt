package com.antivocale.app.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.InferenceService
import com.antivocale.app.transcription.SubtitleTrack
import com.antivocale.app.transcription.TranscriptionLanguagePolicy
import com.antivocale.app.util.AppNotificationChannel
import com.antivocale.app.work.SubtitleChoiceTimeoutWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * TASK-500 code-review F5: the subtitle-choice offer, shared by every entry
 * point that hands the app a video file (the share receiver since GH #82,
 * the History browse FAB since this change). Posts the two-action choice
 * notification and arms the timed ASR fallback, so a picked video behaves
 * exactly like the same file shared.
 */
object SubtitleChoice {

    /**
     * Offers the choice for a video carrying readable text tracks. The
     * notification's actions and the timeout worker both carry [source] and
     * [sourcePackage] verbatim, so the eventual transcription records the
     * real origin (share vs browse).
     */
    fun offer(
        context: Context,
        taskId: String,
        localPath: String,
        track: SubtitleTrack,
        source: String,
        sourcePackage: String?,
        backendOverride: String?,
    ) {
        // TASK-515: one read feeds both the disclosure text and the worker
        // delay, so the notification can never promise a different timeout
        // than the one scheduled (the old constant could drift from the text).
        val timeout = timeoutMinutes(context)
        postChoiceNotification(context, taskId, localPath, track, source, sourcePackage, backendOverride, timeout)
        enqueueTimeoutWorker(context, taskId, localPath, source, sourcePackage, backendOverride, timeout)
    }

    /** The user preference, falling back to the default on any read failure
     *  (the one shared entry-point read helper: round 3 collapsed the
     *  verbatim copies of this idiom, [prefRead] below). */
    private fun timeoutMinutes(context: Context): Int =
        prefRead(context, PreferencesManager.DEFAULT_SUBTITLE_CHOICE_TIMEOUT_MINUTES) { prefs ->
            prefs.subtitleChoiceTimeoutMinutes.first()
        }

    /** Context-only blocking preference read with catch-and-default (the
     *  pattern [pickBestTrack] and [timeoutMinutes] share; one body, so an
     *  idiom fix cannot land in one copy and miss the other). */
    private fun <T> prefRead(
        context: Context,
        fallback: T,
        read: suspend (com.antivocale.app.data.PreferencesManager) -> T,
    ): T = try {
        val preferencesManager = EntryPointAccessors.fromApplication(
            context.applicationContext, SubtitlePrefsEntryPoint::class.java
        ).preferencesManager
        runBlocking { read(preferencesManager) }
    } catch (e: Exception) {
        Log.w(TAG, "Preference read failed, using the fallback", e)
        fallback
    }

    /**
     * Probe + offer in one (reuse-review F-batch): true when the choice was
     * offered and the caller must stop (the prompt owns the request), false
     * when the file has no readable text tracks (or is not a video) and the
     * caller proceeds to ASR. A probe failure logs and proceeds, the share
     * path's historical stance; a fresh taskId rides every offer.
     */
    fun offerIfTracks(
        context: Context,
        taskId: String,
        localPath: String,
        source: String,
        sourcePackage: String?,
        backendOverride: String?,
    ): Boolean {
        if (!com.antivocale.app.util.SharedAudioHandler.isVideoFile(localPath)) return false
        val tracks = try {
            com.antivocale.app.transcription.SubtitleExtractor.probe(localPath)
        } catch (e: Exception) {
            Log.w(TAG, "Subtitle probe failed for $localPath; proceeding to ASR", e)
            emptyList()
        }
        if (tracks.isEmpty()) return false
        offer(
            context,
            taskId,
            localPath,
            pickBestTrack(context, tracks),
            source,
            sourcePackage,
            backendOverride,
        )
        return true
    }

    /**
     * Best-track pick by the saved transcription-language preference; moved
     * verbatim from ShareReceiverActivity (F5 extraction). Auto/system or a
     * blank preference keeps the first track.
     */
    fun pickBestTrack(context: Context, tracks: List<SubtitleTrack>): SubtitleTrack {
        val preferred = prefRead(context, "") { prefs ->
            prefs.transcriptionLanguage.first()
        }
        // TASK-547 review fix (round 2): the preference-to-code mapping is
        // the policy's, never a call site's (repo rule: change the mapping
        // there). resolveOffline collapses the sentinels, the phone pin, and
        // concrete pins exactly the way the recognizer path does, so the
        // subtitle track and the pinned language cannot diverge.
        val resolved = TranscriptionLanguagePolicy.resolveOffline(
            preferred,
            com.antivocale.app.util.LocaleManager.phoneLanguage(context),
        )
        if (resolved.isBlank()) {
            return tracks.first()
        }
        return tracks.firstOrNull { track ->
            track.language != null && (
                track.language.equals(resolved, ignoreCase = true) ||
                    track.language.startsWith(resolved, ignoreCase = true) ||
                    resolved.startsWith(track.language, ignoreCase = true)
                )
        } ?: tracks.first()
    }

    /**
     * The high-priority choice notification (moved from ShareReceiverActivity
     * with source/sourcePackage parameterized). Body tap opens the app; the
     * choice stays on the explicit buttons; swipe-dismiss cancels the timed
     * fallback.
     */
    private fun postChoiceNotification(
        context: Context,
        taskId: String,
        localPath: String,
        track: SubtitleTrack,
        source: String,
        sourcePackage: String?,
        backendOverride: String?,
        timeoutMinutes: Int,
    ) {
        AppNotificationChannel.TRANSCRIPTION_RESULT.create(context)

        val languageLabel = track.language
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.subtitles_language_unknown)

        val baseExtras = Intent().apply {
            putExtra(TaskerRequestReceiver.EXTRA_FILE_PATH, localPath)
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, track.trackIndex)
            sourcePackage?.let { putExtra(ShareReceiverActivity.EXTRA_SOURCE_PACKAGE, it) }
            putExtra(InferenceService.EXTRA_SOURCE, source)
            backendOverride?.let { putExtra(InferenceService.EXTRA_BACKEND_OVERRIDE, it) }
        }

        fun choiceAction(action: String): PendingIntent {
            val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtras(baseExtras)
            }
            return PendingIntent.getBroadcast(
                context,
                (action + taskId).hashCode(),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val choiceText = context.getString(
            R.string.subtitles_found_text,
            languageLabel,
            context.resources.getQuantityString(
                R.plurals.timeout_minutes,
                timeoutMinutes,
                timeoutMinutes,
            ),
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context,
            ("dismiss" + taskId).hashCode(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DISMISS_CHOICE
                putExtras(baseExtras)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            ("open" + taskId).hashCode(),
            Intent(context, com.antivocale.app.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AppNotificationChannel.TRANSCRIPTION_RESULT.id)
            .setContentTitle(context.getString(R.string.subtitles_found_title))
            .setContentText(choiceText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(choiceText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .addAction(
                android.R.drawable.ic_menu_edit,
                context.getString(R.string.action_use_subtitles),
                choiceAction(NotificationActionReceiver.ACTION_USE_SUBTITLES),
            )
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.action_transcribe_audio),
                choiceAction(NotificationActionReceiver.ACTION_TRANSCRIBE_AUDIO),
            )
            .build()

        // Cancel the previous prompt for the SAME file first: a re-offer
        // replaces rather than stacks (code-review finding 8); via the ONE
        // cancel owner (round 4: the site had hand-rolled its own cancel).
        // getSystemService, not NotificationManagerCompat: the Compat variant
        // carries a @RequiresPermission(POST_NOTIFICATIONS) lint annotation;
        // the system service variant is the same call without it (the prompt
        // posts in a share flow where the permission may not be granted yet,
        // and a silently dropped prompt is the accepted degradation).
        context.getSystemService(NotificationManager::class.java)
            .notify(choiceNotificationIdForPath(localPath), notification)
        Log.i(TAG, "Posted subtitle choice notification (taskId=$taskId, language=${track.language}, source=$source)")
    }

    /**
     * The expedited timeout worker (moved verbatim; UNIQUE per taskId so a
     * re-offer replaces the previous pending timeout). Carries [source] so
     * the timed ASR fallback records the real origin; work data persisted by
     * a pre-source build falls back to SOURCE_SHARE (its only origin then).
     */
    private fun enqueueTimeoutWorker(
        context: Context,
        taskId: String,
        localPath: String,
        source: String,
        sourcePackage: String?,
        backendOverride: String?,
        timeoutMinutes: Int,
    ) {
        val request = OneTimeWorkRequestBuilder<SubtitleChoiceTimeoutWorker>()
            .setInitialDelay(timeoutMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    SubtitleChoiceTimeoutWorker.KEY_FILE_PATH to localPath,
                    SubtitleChoiceTimeoutWorker.KEY_TASK_ID to taskId,
                    SubtitleChoiceTimeoutWorker.KEY_SOURCE_PACKAGE to sourcePackage,
                    SubtitleChoiceTimeoutWorker.KEY_BACKEND_OVERRIDE to backendOverride,
                    SubtitleChoiceTimeoutWorker.KEY_SOURCE to source,
                )
            )
            .build()
        // Re-offer of the SAME file must replace its pending timeout, not stack a
        // second full-ASR fallback (efficiency review F-batch: fresh taskIds per
        // pick made the per-taskId name never collide). Cancel sites derive the
        // same name from the file path carried in the notification extras.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(localPath), androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    fun uniqueWorkName(localPath: String): String = "subtitle-choice-${localPath.hashCode()}"

    /**
     * TASK-513/515 review round 3: the ONE prompt cancel. The prompt posts
     * under the PATH-keyed id (re-offers replace); the taskId-banded and
     * raw-hash ids are the pre-TASK-440 legacy a prompt from an older build
     * may still sit under across an in-window app update. Every site that
     * resolves the choice (tap action, timeout worker) calls this; the id
     * scheme cannot diverge again (round 2 fixed the worker and missed the
     * tap path, exactly the divergence this owner deletes).
     */
    fun cancelPrompt(context: Context, filePath: String?, taskId: String?) {
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        filePath?.let { nm.cancel(choiceNotificationIdForPath(it)) }
        if (taskId != null) {
            nm.cancel(ShareReceiverActivity.choiceNotificationId(taskId))
            nm.cancel(taskId.hashCode())
        }
    }

    /**
     * The choice prompt id, keyed on the FILE (code-review: per-taskId ids
     * stacked prompts on re-offers of the same video, and the worker-side
     * per-path fix had left the notification half per-taskId). Same band as
     * the legacy taskId derivation; cancels issue BOTH ids (update window).
     */
    fun choiceNotificationIdForPath(localPath: String): Int =
        com.antivocale.app.service.ResultNotificationFactory.bandedNotificationId(
            localPath.hashCode(), ShareReceiverActivity.CHOICE_ID_BAND_BASE, ShareReceiverActivity.CHOICE_ID_BAND_RANGE)

    private const val TAG = "SubtitleChoice"
}
