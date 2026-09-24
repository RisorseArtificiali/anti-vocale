package com.antivocale.app.util

/**
 * TASK-647: the AI-disclaimer signature applied to every surface where a
 * transcript LEAVES the app (clipboard writes both auto and manual, share
 * intents, file export). One pure assembly consumed by all of them; the
 * stored LogEntity, the notification body, and the in-app History row stay
 * the raw transcript by design (maintainer 2026-09-24: share AND copy are
 * both exits).
 */
object TranscriptSignature {

    /**
     * Assembles [transcript] with [signature] per [position] ("prepend" or
     * "append"). Blank signature or an unknown position returns the transcript
     * unchanged; a blank transcript returns the signature alone (an exit
     * surface never hands out empty text with a dangling separator).
     */
    fun apply(transcript: String, signature: String, position: String): String {
        val sig = signature.trim()
        if (sig.isEmpty()) return transcript
        val text = transcript.trim()
        if (text.isEmpty()) return sig
        return when (position) {
            "prepend" -> "$sig\n$text"
            else -> "$text\n$sig"
        }
    }
}
