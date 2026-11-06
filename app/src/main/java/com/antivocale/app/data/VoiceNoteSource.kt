package com.antivocale.app.data

import android.os.Environment
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * The sources the automatic voice-note pipeline can watch: the two
 * WhatsApp clients (each a fixed Android/media tree) plus TASK-966's
 * generic [WATCHED_FOLDER] source (GH #184, Signal's Music/Signal among
 * others), whose watch root is a user-picked SAF tree. For the WhatsApp
 * pair each entry is the whole per-client difference: the package that
 * stamps History rows and keys the sender match, the media tree the
 * detector watches, the folder name the picker validates, and the id
 * namespace that keeps two clients' notes apart. Everything else
 * (detector, grant resolve, enqueue, the identity listener) is shared
 * code parameterized by the source.
 *
 * [idNamespace] is empty for [WHATSAPP] on purpose: existing task ids
 * ("voicenote_<PTT name>") and sandbox copies must stay byte-identical so a
 * completed row keeps deduping after the update. Business notes use the
 * same PTT-YYYYMMDD-WANNNNN naming with their own counter, so without a
 * namespace two different notes from the two apps could share an id and
 * the second would be swallowed as "already transcribed".
 */
enum class VoiceNoteSource(
    /** Null for [WATCHED_FOLDER]: no single app stamps its rows (the
     *  History row lands ungrouped) and no notification package can ever
     *  resolve to it. */
    val packageName: String?,
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
    ),
    WATCHED_FOLDER(
        packageName = null,
        appDirName = "",
        folderName = "",
        idNamespace = "watched_",
    );

    /**
     * TASK-966: the source watches WhatsApp's PTT tree shape (numeric
     * counter folders, PTT-*.opus names, the watermark-keyed recovery
     * sweep). The watched-folder source does not: it watches the flat
     * user-picked folder itself, live CLOSE_WRITE/MOVED_TO events only,
     * and ordinary audio file names (no counter children to watch, no
     * ordering a watermark key could ride).
     */
    val usesPttLayout: Boolean get() = this != WATCHED_FOLDER

    /**
     * TASK-966: whether the sender-label machinery applies. Only the
     * WhatsApp clients mine notifications; the watched-folder source has
     * no originating package to key the identity match, so its notes are
     * never labeled (and never pay the matcher's race-window wait).
     */
    val resolvesSenderLabels: Boolean get() = packageName != null

    /**
     * The path under the external storage root (also the picker's initial
     * location). WhatsApp sources only: [WATCHED_FOLDER]'s root is the
     * user's picked tree (no fixed path exists), so reading this for it
     * fails loudly instead of synthesizing a garbage path a silent
     * consumer would watch.
     */
    val relativeRootPath: String
        get() {
            checkPackageName()
            return "Android/media/$packageName/$appDirName/Media/$folderName"
        }

    /** The canonical voice-notes folder (the detector's watch root).
     *  WhatsApp sources only; see [relativeRootPath]. */
    fun root(): File {
        checkPackageName()
        return File(Environment.getExternalStorageDirectory(), relativeRootPath)
    }

    private fun checkPackageName() {
        check(packageName != null) {
            "$name has no fixed root: the watch root is the user's picked tree"
        }
    }

    /**
     * Whether a SAF document path ("Android/media/com.whatsapp/...", the part
     * after the volume colon) lies inside THIS client's storage: the scoped
     * media dir, or the pre-scoped-storage "<appDirName>/" tree. The trailing
     * slash keeps com.whatsapp from matching com.whatsapp.w4b and "WhatsApp/"
     * from matching "WhatsApp Business/". [WATCHED_FOLDER] owns no path (a
     * null package matches nothing), so the cross-source check can hand the
     * WhatsApp trees back to their own slots when picked into the generic one.
     */
    fun ownsDocumentPath(documentPath: String): Boolean =
        packageName != null && (
            documentPath.startsWith("Android/media/$packageName/") ||
                documentPath.startsWith("$appDirName/")
            )

    companion object {
        /** The source a History/notification package belongs to, or null.
         *  The watched-folder source has no package and can never match. */
        fun forPackage(packageName: String?): VoiceNoteSource? =
            entries.firstOrNull { it.packageName != null && it.packageName == packageName }
    }
}

/** The per-source detection gate (the WhatsApp entry keeps its original key). */
fun PreferencesManager.voiceNoteDetectionEnabled(source: VoiceNoteSource): Flow<Boolean> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteDetectionEnabled
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessDetectionEnabled
        VoiceNoteSource.WATCHED_FOLDER -> voiceNoteWatchedDetectionEnabled
    }

suspend fun PreferencesManager.saveVoiceNoteDetectionEnabled(source: VoiceNoteSource, enabled: Boolean) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> saveVoiceNoteDetectionEnabled(enabled)
        VoiceNoteSource.WHATSAPP_BUSINESS -> saveVoiceNoteBusinessDetectionEnabled(enabled)
        VoiceNoteSource.WATCHED_FOLDER -> saveVoiceNoteWatchedDetectionEnabled(enabled)
    }

/** The per-source SAF tree grant URI (device-bound, never in backups). */
fun PreferencesManager.voiceNoteTreeUri(source: VoiceNoteSource): Flow<String?> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteTreeUri
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessTreeUri
        VoiceNoteSource.WATCHED_FOLDER -> voiceNoteWatchedTreeUri
    }

suspend fun PreferencesManager.saveVoiceNoteTreeUri(source: VoiceNoteSource, uri: String?) =
    when (source) {
        VoiceNoteSource.WHATSAPP -> saveVoiceNoteTreeUri(uri)
        VoiceNoteSource.WHATSAPP_BUSINESS -> saveVoiceNoteBusinessTreeUri(uri)
        VoiceNoteSource.WATCHED_FOLDER -> saveVoiceNoteWatchedTreeUri(uri)
    }

/**
 * TASK-891: the per-source catch-up watermark. The two clients' counter
 * trees are independent number spaces, so each keeps its own; the WhatsApp
 * entry keeps its original key, so released installs (which never ran a
 * Business detector) migrate by construction. A pre-release shared-key
 * build may have mixed Business keys into it; bounded, one-time (see
 * TASK-891's notes).
 *
 * TASK-966: the watched-folder source has NO keyed recovery (ordinary
 * file names carry no ordering; the detector skips the watermark-gated
 * sweep for it), so its arms are constants that keep the accessors total
 * over the enum without a preference key nothing would ever write. If a
 * keyed scheme arrives for it, give it real keys like the Business twin.
 */
fun PreferencesManager.voiceNoteWatermark(source: VoiceNoteSource): Flow<String?> =
    when (source) {
        VoiceNoteSource.WHATSAPP -> voiceNoteWatermark
        VoiceNoteSource.WHATSAPP_BUSINESS -> voiceNoteBusinessWatermark
        VoiceNoteSource.WATCHED_FOLDER -> kotlinx.coroutines.flow.flowOf(null)
    }

suspend fun PreferencesManager.bumpVoiceNoteWatermark(source: VoiceNoteSource, candidate: String) {
    when (source) {
        VoiceNoteSource.WHATSAPP -> bumpVoiceNoteWatermark(candidate)
        VoiceNoteSource.WHATSAPP_BUSINESS -> bumpVoiceNoteBusinessWatermark(candidate)
        VoiceNoteSource.WATCHED_FOLDER -> Unit
    }
}

suspend fun PreferencesManager.resetVoiceNoteWatermark(source: VoiceNoteSource) {
    when (source) {
        VoiceNoteSource.WHATSAPP -> resetVoiceNoteWatermark()
        VoiceNoteSource.WHATSAPP_BUSINESS -> resetVoiceNoteBusinessWatermark()
        VoiceNoteSource.WATCHED_FOLDER -> Unit
    }
}
