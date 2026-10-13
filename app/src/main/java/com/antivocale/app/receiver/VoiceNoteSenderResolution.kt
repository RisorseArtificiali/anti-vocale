package com.antivocale.app.receiver

import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor

/**
 * The sender-correlation step of the share flow (TASK-736, matching a
 * shared audio at dispatch time over the RAM identity cache). One
 * implementation of the duration probe and of the matcher invocation, so
 * the rule cannot drift: no probed duration, no label (a wrong name is
 * the one outcome worse than no name). The detection route posts
 * sender-less offers by design (its paths are FUSE-hidden, code review
 * F1 2026-10-13); restoring detection-time enrichment goes through here.
 */
object VoiceNoteSenderResolution {

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
