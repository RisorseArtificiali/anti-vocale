package com.antivocale.app.data

import android.os.Environment
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * The messaging clients the automatic voice-note pipeline can watch, one
 * entry per client (WhatsApp Business support). Each entry is the whole
 * per-client difference: the package that stamps History rows and keys the
 * sender match, the media tree the detector watches, the folder name the
 * picker validates, and the id namespace that keeps two clients' notes
 * apart. Everything else (detector, grant resolve, enqueue, the identity
 * listener) is shared code parameterized by the source.
 *
 * [idNamespace] is empty for [WHATSAPP] on purpose: existing task ids
 * ("voicenote_<PTT name>") and sandbox copies must stay byte-identical so a
 * completed row keeps deduping after the update. Business notes use the
 * same PTT-YYYYMMDD-WANNNNN naming with their own counter, so without a
 * namespace two different notes from the two apps could share an id and
 * the second would be swallowed as "already transcribed".
 */
enum class VoiceNoteSource(
    val packageName: String,
    /** The client's app folder name (Android/media/<pkg>/<appDirName>/Media/...). */
    val appDirName: String,
    /** The voice-notes folder name (not localized by WhatsApp). */
    val folderName: String,
    val idNamespace: String,
) {
    WHATSAPP(
        packageName = PerAppPreferencesManager.WHATSAPP,
        appDirName = "WhatsApp",
        folderName = "WhatsApp Voice Notes",
        idNamespace = "",
    ),
    WHATSAPP_BUSINESS(
        packageName = "com.whatsapp.w4b",
        appDirName = "WhatsApp Business",
        folderName = "WhatsApp Business Voice Notes",
        idNamespace = "w4b_",
    );

    /** The path under the external storage root (also the picker's initial location). */
    val relativeRootPath: String
        get() = "Android/media/$packageName/$appDirName/Media/$folderName"

    /** The canonical voice-notes folder (the detector's watch root). */
    fun root(): File = File(Environment.getExternalStorageDirectory(), relativeRootPath)

    /**
     * Whether a SAF document path ("Android/media/com.whatsapp/...", the part
     * after the volume colon) lies inside THIS client's storage: the scoped
     * media dir, or the pre-scoped-storage "<appDirName>/" tree. The trailing
     * slash keeps com.whatsapp from matching com.whatsapp.w4b and "WhatsApp/"
     * from matching "WhatsApp Business/".
     */
    fun ownsDocumentPath(documentPath: String): Boolean =
        documentPath.startsWith("Android/media/$packageName/") ||
            documentPath.startsWith("$appDirName/")

    companion object {
        /** The source a History/notification package belongs to, or null. */
        fun forPackage(packageName: String?): VoiceNoteSource? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

/** The per-source detection gate (the WhatsApp entry keeps its original key). */
fun PreferencesManager.voiceNoteDetectionEnabled(source: VoiceNoteSource): Flow<Boolean> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteDetectionEnabled
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessDetectionEnabled
    }

suspend fun PreferencesManager.saveVoiceNoteDetectionEnabled(source: VoiceNoteSource, enabled: Boolean) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> saveVoiceNoteDetectionEnabled(enabled)
        VoiceNoteSource.WHATSAPP_BUSINESS -> saveVoiceNoteBusinessDetectionEnabled(enabled)
    }

/** The per-source SAF tree grant URI (device-bound, never in backups). */
fun PreferencesManager.voiceNoteTreeUri(source: VoiceNoteSource): Flow<String?> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteTreeUri
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessTreeUri
    }

suspend fun PreferencesManager.saveVoiceNoteTreeUri(source: VoiceNoteSource, uri: String?) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> saveVoiceNoteTreeUri(uri)
        VoiceNoteSource.WHATSAPP_BUSINESS -> saveVoiceNoteBusinessTreeUri(uri)
    }

/**
 * TASK-891: the per-source catch-up watermark. The two clients' counter
 * trees are independent number spaces, so each keeps its own; the WhatsApp
 * entry keeps its original key, so released installs (which never ran a
 * Business detector) migrate by construction. A pre-release shared-key
 * build may have mixed Business keys into it; bounded, one-time (see
 * TASK-891's notes).
 */
fun PreferencesManager.voiceNoteWatermark(source: VoiceNoteSource): Flow<String?> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteWatermark
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessWatermark
    }

suspend fun PreferencesManager.bumpVoiceNoteWatermark(source: VoiceNoteSource, candidate: String) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> bumpVoiceNoteWatermark(candidate)
        VoiceNoteSource.WHATSAPP_BUSINESS -> bumpVoiceNoteBusinessWatermark(candidate)
    }

suspend fun PreferencesManager.resetVoiceNoteWatermark(source: VoiceNoteSource) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> resetVoiceNoteWatermark()
        VoiceNoteSource.WHATSAPP_BUSINESS -> resetVoiceNoteBusinessWatermark()
    }
