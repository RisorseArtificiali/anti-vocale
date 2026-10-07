package com.antivocale.app.receiver

import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor

/**
 * TASK-271: the shared sender-correlation step behind both consumers of the
 * RAM identity cache: the share flow (TASK-736, matching a shared audio at
 * dispatch time) and the detection coordinator (TASK-271, matching a
 * detected voice note at detection time). One implementation of the duration
 * probe and of the matcher invocation, so the two paths cannot drift on the
 * evidence rule: no probed duration, no label (a wrong name is the one
 * outcome worse than no name).
 */
object VoiceNoteSenderResolution {

    /** The facts a caller needs: the matched label, and the duration that probed it. */
    data class Result(
        val senderName: String?,
        val durationSeconds: Long?,
    )

    /**
     * The HOUSE duration probe, not a raw MediaMetadataRetriever: voice notes
     * are Ogg Opus, where the retriever's KEY_DURATION is the
     * documented-unreliable path (GH #91); this one reads the granule
     * position first. Returns seconds, or null when the probe failed or
     * read a non-positive value.
     */
    fun probeDurationSeconds(audioPreprocessor: AudioPreprocessor, localPath: String): Long? =
        runCatching { audioPreprocessor.getAudioDuration(localPath) }
            .getOrNull()?.takeIf { it > 0 }?.toLong()

    /**
     * The share path's entry: free short-circuit when nothing is cached for
     * the calling package (the common case; every ordinary share pays
     * nothing), otherwise one duration probe plus the match.
     */
    fun matchForShare(
        cache: VoiceNoteIdentityCache,
        audioPreprocessor: AudioPreprocessor,
        sourcePackage: String?,
        localPath: String,
    ): String? {
        val candidates = cache.forPackage(sourcePackage.orEmpty())
        if (candidates.isEmpty()) {
            Log.i(TAG, "identity match: no cached candidates for ${sourcePackage.orEmpty()}")
            return null
        }
        return selectAndLog(candidates, probeDurationSeconds(audioPreprocessor, localPath))?.sender
    }

    /**
     * The detection path's entry: the duration is probed unconditionally
     * (the offer payload carries it even when no identity is cached), then
     * the same matcher runs over the same cache for the contributing
     * package. Ambiguity or mismatch degrades to a sender-less offer, which
     * is already the matcher's contract.
     */
    fun match(
        cache: VoiceNoteIdentityCache,
        audioPreprocessor: AudioPreprocessor,
        sourcePackage: String?,
        localPath: String,
    ): Result {
        val durationSeconds = probeDurationSeconds(audioPreprocessor, localPath)
        val candidates = cache.forPackage(sourcePackage.orEmpty())
        if (candidates.isEmpty()) {
            Log.i(TAG, "identity match: no cached candidates for ${sourcePackage.orEmpty()}")
            return Result(null, durationSeconds)
        }
        return Result(selectAndLog(candidates, durationSeconds)?.sender, durationSeconds)
    }

    /**
     * E2E instrumentation (the TASK-736 field report was a bare "no label"):
     * counts and seconds only, no names in logcat, separating nothing-cached
     * (logged at the call sites), unreadable duration, and disagreement.
     */
    private fun selectAndLog(
        candidates: List<VoiceNoteIdentityExtractor.VoiceNote>,
        durationSeconds: Long?,
    ): VoiceNoteIdentityExtractor.VoiceNote? {
        val verdict = VoiceNoteIdentityMatcher.select(candidates, durationSeconds)
        Log.i(
            TAG,
            "identity match: ${candidates.size} candidate(s), audio ${durationSeconds}s, " +
                "durations ${candidates.mapNotNull { it.durationSeconds }}, " +
                "labeled=${verdict != null}")
        return verdict
    }

    private const val TAG = "VoiceNoteIdentity"
}
