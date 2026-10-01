package com.antivocale.app.receiver

/**
 * TASK-736: picks which cached voice-note identity (if any) a shared audio
 * is. The decision rules, evidence-bound:
 *
 * - Candidates are the listener's cache entries for the SAME calling
 *   package, newest first, already TTL-pruned by the cache.
 * - When the share's audio duration is known, a candidate counts only if
 *   the notification's "(m:ss)" marker agrees within [DURATION_TOLERANCE_S]:
 *   the marker is the spike-verified WhatsApp evidence and disagreement
 *   means it is a DIFFERENT note. No agreeing candidate and a known
 *   duration: NO label (no guessing), the honest answer for a stale cache.
 * - Without a probed duration the newest candidate wins (recency-only; the
 *   cache only ever holds marker-bearing voice notes, so the class of
 *   candidate is right even when the specific note is ambiguous).
 * - Two notes of the same duration inside the window resolve to the NEWEST
 *   (best-effort; the collision is rare and the label is metadata, not
 *   gospel).
 */
object VoiceNoteIdentityMatcher {

    /** WhatsApp rounds to whole seconds; the decode adds a fraction. */
    const val DURATION_TOLERANCE_S = 1L

    /**
     * Pure selection over already-filtered candidates (newest first).
     * [audioDurationSeconds] null when the probe could not read it.
     */
    fun select(
        candidates: List<VoiceNoteIdentityExtractor.VoiceNote>,
        audioDurationSeconds: Long?,
    ): VoiceNoteIdentityExtractor.VoiceNote? {
        if (candidates.isEmpty()) return null
        if (audioDurationSeconds == null) return candidates.first()
        return candidates.firstOrNull {
            it.durationSeconds != null &&
                kotlin.math.abs(it.durationSeconds - audioDurationSeconds) <= DURATION_TOLERANCE_S
        }
    }
}
