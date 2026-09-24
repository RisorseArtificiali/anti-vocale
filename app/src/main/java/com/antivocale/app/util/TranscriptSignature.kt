package com.antivocale.app.util

import kotlinx.coroutines.flow.first

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


    /** TASK-647: the resolved pair every exit surface consumes. */
    data class Spec(val text: String, val position: String)

    /**
     * The one seam (code review F8): text + position resolved together,
     * fail-open (a preferences failure must never cost the result
     * notification), defaulting to the declared constants.
     */
    /**
     * TASK-650 F5: the last resolution, readable from non-suspend exit sites
     * (the History copy/share helpers). Updated by every [effectiveSpec]
     * call and by the view models while their screens are subscribed, so a
     * user-visible copy sees the live preference state.
     */
    @Volatile
    var lastResolved: Spec = Spec("", com.antivocale.app.data.PreferencesManager.DEFAULT_SIGNATURE_POSITION)

    /**
     * True once any resolution ran in THIS process: the snapshot then
     * reflects the live preferences (including "off"), so rebuild surfaces
     * prefer it over values baked at notification-post time (TASK-650 F7).
     */
    @Volatile
    var lastResolvedIsLive: Boolean = false

    suspend fun effectiveSpec(
        preferences: com.antivocale.app.data.PreferencesManager,
        defaultText: String,
    ): Spec = runCatching {
        val enabled = preferences.signatureEnabled.first()
        val text = preferences.signatureText.first().ifBlank { defaultText }
        Spec(
            text = if (enabled) text else "",
            position = preferences.signaturePosition.first()
                .ifBlank { com.antivocale.app.data.PreferencesManager.DEFAULT_SIGNATURE_POSITION },
        )
    }.getOrDefault(Spec("", com.antivocale.app.data.PreferencesManager.DEFAULT_SIGNATURE_POSITION))
        .also {
            lastResolved = it
            lastResolvedIsLive = true
        }
}
