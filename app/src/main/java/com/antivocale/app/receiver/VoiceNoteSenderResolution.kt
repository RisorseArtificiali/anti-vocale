package com.antivocale.app.receiver

import android.util.Log
import com.antivocale.app.audio.AudioPreprocessor
import kotlinx.coroutines.delay

/**
 * The sender-correlation step (TASK-736: matching an audio at dispatch
 * time over the RAM identity cache; TASK-821: the automatic route rides
 * the same rules on its sandbox copy). One implementation of the duration
 * probe and of the matcher invocation, so the rule cannot drift: no
 * probed duration, no label (a wrong name is the one outcome worse than
 * no name).
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
     * The dispatch-time entry for the SHARE flow (the automatic route
     * rides [matchForDetection], which adds its race window and candidate
     * window): free short-circuit when nothing is cached for the calling
     * package (the common case; every ordinary share pays nothing),
     * otherwise one duration probe plus the match. Candidates span the
     * cache's whole TTL, which is right for a share: it happens long
     * after the notification, and the newest duration-agreeing entry IS
     * the shared note.
     */
    fun match(
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
     * TASK-821: the automatic route's entry, bound to the detection
     * event's time. Candidates are only notifications posted within
     * [CANDIDATE_WINDOW_MS] of the file event: the matcher is
     * duration-based, so an OLDER same-length note (minutes or hours
     * back, inside the cache's TTL) would otherwise answer for this one
     * and write the PREVIOUS sender's name on this note.
     *
     * One attempt, one bounded re-check after [RACE_WINDOW_MS] whenever
     * the first labels nothing, then label-less. The re-check covers BOTH
     * races: the note's own notification posting after its file event,
     * AND a first check answered by a DIFFERENT in-window note whose
     * marker disagreed (only the note's own notification agreeing is a
     * verdict; a disagreement proves nothing, review R2-2). Notes that
     * never notify (the user's own outgoing ones) pay the window once
     * each; users who never enabled the feature pay nothing (the
     * accepting gate first: no wait, no probe, no log).
     *
     * KNOWN LIMITATION (TASK-833): with media auto-download off, the
     * notification can post MINUTES before the user's tap writes the
     * file; such notes sit outside the window, pay the wait, and stay
     * unlabeled. Widening the window is deliberately NOT done here: it
     * would label outgoing notes with the previous sender's name.
     */
    suspend fun matchForDetection(
        cache: VoiceNoteIdentityCache,
        audioPreprocessor: AudioPreprocessor,
        sourcePackage: String?,
        localPath: String,
        detectedAtMs: Long,
    ): String? {
        if (!cache.accepting) return null
        selectInWindow(cache, audioPreprocessor, sourcePackage, localPath, detectedAtMs)?.let { return it }
        Log.i(TAG, "identity match: unlabeled on first check for ${sourcePackage.orEmpty()}, awaiting the race window")
        delay(RACE_WINDOW_MS)
        return selectInWindow(cache, audioPreprocessor, sourcePackage, localPath, detectedAtMs)
    }

    private fun selectInWindow(
        cache: VoiceNoteIdentityCache,
        audioPreprocessor: AudioPreprocessor,
        sourcePackage: String?,
        localPath: String,
        detectedAtMs: Long,
    ): String? {
        val candidates = inWindow(cache, sourcePackage, detectedAtMs)
        if (candidates.isEmpty()) {
            // TASK-833 telemetry: the two unlabeled causes are different
            // user classes (no notification at all vs the deferred-download
            // window miss where the right notification sits in the cache
            // outside the 60s window); the weekly log mix sizes them.
            Log.i(TAG, "identity match: no candidates in window " +
                "(${sourcePackage.orEmpty()}, detected $detectedAtMs)")
            return null
        }
        // One notification, one note: the entry that labels this detection
        // can never label a later one (the race-window re-check then waits
        // for the later note's OWN notification instead).
        return selectAndLog(candidates, probeDurationSeconds(audioPreprocessor, localPath))
            ?.also { cache.markConsumed(it) }
            ?.sender
    }

    private fun inWindow(
        cache: VoiceNoteIdentityCache,
        sourcePackage: String?,
        detectedAtMs: Long,
    ): List<VoiceNoteIdentityExtractor.VoiceNote> =
        cache.unconsumedForPackage(sourcePackage.orEmpty()).filter {
            kotlin.math.abs(it.postedAtMs - detectedAtMs) <= CANDIDATE_WINDOW_MS
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

    /** One re-check; the notification-vs-file race is seconds at most. */
    private const val RACE_WINDOW_MS = 2_000L

    /**
     * How far a candidate notification may sit from the file event: the
     * note's own notification leads or lags the write by seconds; a note
     * from minutes earlier is a DIFFERENT note however close its length.
     */
    private const val CANDIDATE_WINDOW_MS = 60_000L
}
