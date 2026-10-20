package com.antivocale.app.service

import android.content.Intent
import com.antivocale.app.receiver.NotificationActionReceiver

/**
 * TASK-764: the ONE nav-intent round trip for [ResultNotificationSpec]:
 * every round-tripping field appears exactly once per direction here, and
 * the reflection-enumerated symmetry test fails the build when a new spec
 * field misses the pair.
 *
 * Key compatibility: the string values are FROZEN. Notifications posted
 * before an app update carry the old extras and must still repost, so a
 * value change strands them (a pre-field intent reposts with that field
 * at its default). Keys shared with other intents (transcription text,
 * source package, task id) keep their [NotificationActionReceiver]
 * constants (the sender name keeps InferenceService.EXTRA_SENDER_NAME);
 * the other eighteen are private here.
 */
object ResultNotificationSpecNav {

    private const val EXTRA_SIGNATURE_TEXT = "signature_text"
    private const val EXTRA_SIGNATURE_POSITION = "signature_position"
    private const val EXTRA_PAGE_INDEX = "page_index"
    private const val EXTRA_NOTIFICATION_ID = "notification_id"
    private const val EXTRA_FIRST_POSTED_AT = "first_posted_at"
    private const val EXTRA_CONFIDENCE = "confidence"
    private const val EXTRA_DETECTED_LANGUAGE = "detected_language"
    private const val EXTRA_REPETITION_SUSPECTED = "repetition_suspected"
    private const val EXTRA_COPIED_TO_CLIPBOARD = "copied_to_clipboard"
    private const val EXTRA_STREAMED_WITHOUT_VAD = "streamed_without_vad"
    private const val EXTRA_REFINED_FROM = "refined_from"
    private const val EXTRA_NOT_REFINED = "not_refined"
    private const val EXTRA_IS_PARTIAL = "is_partial"
    private const val EXTRA_FAILED_CHUNK_COUNT = "failed_chunk_count"
    private const val EXTRA_SAVE_FAILURE = "save_failure"
    private const val EXTRA_SAVED_FOLDER = "saved_folder"
    private const val EXTRA_SAVED_FILE = "saved_file"
    private const val EXTRA_FROM_VOICE_NOTE = "from_voice_note"

    /**
     * Writes every round-tripping field of [spec] into [intent].
     * [currentPage] is the page the tapped notification displays; the
     * refresher derives the target page from it and the tap's action.
     */
    fun writeTo(intent: Intent, spec: ResultNotificationSpec, currentPage: Int) {
        with(intent) {
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, spec.transcriptionText)
            // TASK-647: the rebuilt notification's copy/share actions must
            // keep signing; the body stays raw.
            putExtra(EXTRA_SIGNATURE_TEXT, spec.signatureText)
            putExtra(EXTRA_SIGNATURE_POSITION, spec.signaturePosition)
            putExtra(EXTRA_PAGE_INDEX, currentPage)
            putExtra(EXTRA_SAVED_FOLDER, spec.savedFolderName)
            spec.savedFileName?.let { putExtra(EXTRA_SAVED_FILE, it) }
            putExtra(EXTRA_NOTIFICATION_ID, spec.notificationId)
            putExtra(EXTRA_FIRST_POSTED_AT, spec.firstPostedAt)
            putExtra(EXTRA_SAVE_FAILURE, spec.saveFailureReason)
            putExtra(EXTRA_IS_PARTIAL, spec.isPartial)
            putExtra(EXTRA_FAILED_CHUNK_COUNT, spec.failedChunkCount)
            putExtra(EXTRA_REPETITION_SUSPECTED, spec.repetitionSuspected)
            putExtra(EXTRA_COPIED_TO_CLIPBOARD, spec.copiedToClipboard)
            putExtra(EXTRA_STREAMED_WITHOUT_VAD, spec.streamedWithoutVad)
            spec.refinedFrom?.let { putExtra(EXTRA_REFINED_FROM, it) }
            putExtra(EXTRA_NOT_REFINED, spec.notRefined)
            spec.taskId?.let { putExtra(NotificationActionReceiver.EXTRA_TASK_ID, it) }
            spec.sourcePackage?.let { putExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE, it) }
            spec.confidence?.let { putExtra(EXTRA_CONFIDENCE, it) }
            spec.detectedLanguage?.let { putExtra(EXTRA_DETECTED_LANGUAGE, it) }
            putExtra(EXTRA_FROM_VOICE_NOTE, spec.fromVoiceNote)
            spec.senderName?.let { putExtra(InferenceService.EXTRA_SENDER_NAME, it) }
        }
    }

    /**
     * Reads the spec back. Null when the text or the notification id is
     * absent (a malformed or foreign intent; the caller logs and drops).
     * Absent optional fields come back at their defaults, which is what
     * keeps a notification posted before its field existed reposting
     * across an app update.
     */
    fun readFrom(intent: Intent): ResultNotificationSpec? {
        val text = intent.getStringExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (text.isNullOrBlank() || notificationId == -1) return null
        return ResultNotificationSpec(
            transcriptionText = text,
            signatureText = intent.getStringExtra(EXTRA_SIGNATURE_TEXT) ?: "",
            signaturePosition = intent.getStringExtra(EXTRA_SIGNATURE_POSITION) ?: "append",
            taskId = intent.getStringExtra(NotificationActionReceiver.EXTRA_TASK_ID),
            sourcePackage = intent.getStringExtra(NotificationActionReceiver.EXTRA_SOURCE_PACKAGE),
            confidence = if (intent.hasExtra(EXTRA_CONFIDENCE)) {
                intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
            } else null,
            detectedLanguage = intent.getStringExtra(EXTRA_DETECTED_LANGUAGE),
            isPartial = intent.getBooleanExtra(EXTRA_IS_PARTIAL, false),
            failedChunkCount = intent.getIntExtra(EXTRA_FAILED_CHUNK_COUNT, 0),
            saveFailureReason = intent.getStringExtra(EXTRA_SAVE_FAILURE),
            savedFolderName = intent.getStringExtra(EXTRA_SAVED_FOLDER),
            savedFileName = intent.getStringExtra(EXTRA_SAVED_FILE),
            repetitionSuspected = intent.getBooleanExtra(EXTRA_REPETITION_SUSPECTED, false),
            copiedToClipboard = intent.getBooleanExtra(EXTRA_COPIED_TO_CLIPBOARD, false),
            streamedWithoutVad = intent.getBooleanExtra(EXTRA_STREAMED_WITHOUT_VAD, false),
            refinedFrom = intent.getStringExtra(EXTRA_REFINED_FROM),
            notRefined = intent.getBooleanExtra(EXTRA_NOT_REFINED, false),
            pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0),
            notificationId = notificationId,
            firstPostedAt = intent.getLongExtra(EXTRA_FIRST_POSTED_AT, System.currentTimeMillis()),
            fromVoiceNote = intent.getBooleanExtra(EXTRA_FROM_VOICE_NOTE, false),
            senderName = intent.getStringExtra(InferenceService.EXTRA_SENDER_NAME),
        )
    }
}
