package com.antivocale.app.receiver

/**
 * TASK-735: the pure half of the voice-note identity listener. Decides,
 * from a notification's plain fields, whether it is a messaging-app VOICE
 * note and who sent it. The shape is the one the 2026-10-01 TASK-269 spike
 * verified on a live device:
 *
 * - WhatsApp: the sender sits in the notification TITLE (not the
 *   MessagingStyle Person); the voice marker rides the text as
 *   "🎤 Voice message (0:04)" with the duration in parentheses.
 * - Telegram: NOT accepted yet. The spike verified no Telegram voice-note
 *   text marker, so the package gate below admits WhatsApp only; without a
 *   verified voice discriminator a plain TEXT message would match and
 *   attribute the wrong sender to the next shared audio (the GigaAM lesson
 *   applied to notification shapes). Telegram support lands when its marker
 *   is captured on a device, as a pattern addition here and nothing else.
 *
 * CONSCIOUS BOUNDARY: WhatsApp's marker string is LOCALIZED (the spike saw
 * the English form on an Italian device; one data point, not a rule).
 * Detection keys on the 🎤 emoji plus a parenthesized m:ss/h:mm:ss
 * duration; a locale that changes either is skipped and the row stays
 * unlabeled (no guessing).
 *
 * Pure by design: no Android types, so the classification and the duration
 * parse are unit-testable; the listener shell
 * ([VoiceNoteIdentityListener]) only adapts StatusBarNotification to this.
 */
object VoiceNoteIdentityExtractor {

    /** One recognized voice note: who sent it, and the duration from the marker. */
    data class VoiceNote(
        val packageName: String,
        val sender: String,
        /** Seconds, from the "(m:ss)" marker. */
        val durationSeconds: Long?,
        val postedAtMs: Long,
    )

    /**
     * The voice marker as observed in the spike: the microphone emoji, any
     * text, then the duration in parentheses at the end. The emoji and the
     * parenthesized duration are the stable part; the words between are the
     * localized bit we do not pin.
     */
    private val VOICE_MARKER = Regex("""🎤[^(]*\((\d{1,2}:\d{2}(?::\d{2})?)\)\s*$""")

    /** "(m:ss)" or "(h:mm:ss)" to seconds; null when [secondsText] did not parse. */
    internal fun parseDurationSeconds(secondsText: String): Long? {
        // Total: a single unparsable segment rejects the whole text (a
        // partial parse would confidently feed the 1s match tolerance a
        // wrong duration).
        val parts = secondsText.split(":").map { it.toLongOrNull() }
        if (parts.any { it == null }) return null
        return when (parts.size) {
            2 -> parts[0]!! * 60 + parts[1]!!
            3 -> parts[0]!! * 3600 + parts[1]!! * 60 + parts[2]!!
            else -> null
        }
    }

    /**
     * Classifies one notification. [title] is EXTRA_TITLE, [text] the
     * notification body (the marker), [conversationTitle] the MessagingStyle
     * conversation title, [messagingPersons] lazily supplies the per-message
     * Person names (invoked only after the marker matched, so callers pay
     * the unmarshal only for real voice notes), [isGroupSummary] skips
     * group-summary posts. Sender resolution is GROUP-SAFE by evidence: in
     * a WhatsApp group the TITLE is the GROUP name, so the title is trusted
     * ONLY when no conversation title is set (the verified 1:1 shape); when
     * a per-message Person exists it wins everywhere (the group shape).
     * Returns null for everything that is not a recognized voice note with
     * a trustworthy sender.
     */
    fun fromNotification(
        category: String?,
        packageName: String,
        title: CharSequence?,
        text: CharSequence?,
        conversationTitle: CharSequence?,
        messagingPersons: () -> List<String?>,
        isGroupSummary: Boolean,
        postedAtMs: Long,
    ): VoiceNote? {
        // The privacy contract is a CONJUNCTION: message category AND a
        // messaging package (a spoofed or unrelated app posting category msg
        // must not have its titles cached as senders).
        if (category != "msg" || !isMessagingPackage(packageName)) return null
        if (isGroupSummary) return null

        val textString = text?.toString().orEmpty()
        val match = VOICE_MARKER.find(textString) ?: run {
            // TASK-736 E2E chase + range review: a text carrying the mic
            // emoji but NOT the expected "(m:ss)" tail is a voice note
            // whose notification shape drifted - OR an ordinary TEXT
            // message that happens to contain the emoji, which is user
            // content. The log therefore carries the SHAPE only (length,
            // presence of a parenthesized tail), never the text.
            if (textString.contains("\uD83C\uDFA4")) {
                android.util.Log.w(
                    "VoiceNoteIdentity",
                    "voice-note marker drift: len=${textString.length} " +
                        "parenTail=${Regex("\\)\\s*$").containsMatchIn(textString)}")
            }
            return null
        }

        // ONLY the newest message's Person (the last of the style): a Person
        // from an OLDER message names the wrong speaker, never this note.
        val person = messagingPersons().lastOrNull()?.trim()
        val sender = when {
            // Per-message Person: group-safe (names the actual speaker).
            !person.isNullOrEmpty() -> person
            // 1:1 shape: the title IS the sender, but only when the post
            // carries no conversation title (a set one means group, where
            // the title is the GROUP name: misattribution, skip).
            conversationTitle == null -> title?.toString()?.trim().orEmpty()
            else -> ""
        }
        if (sender.isEmpty()) return null
        return VoiceNote(
            packageName = packageName,
            sender = sender,
            durationSeconds = parseDurationSeconds(match.groupValues[1]),
            postedAtMs = postedAtMs,
        )
    }

    /**
     * EXACT ids only (review: a substring match let com.foo.whatsappclone
     * through): the packages the spike verified. Telegram joins with its
     * verified marker, not its package substring.
     */
    private val MESSAGING_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun isMessagingPackage(pkg: String): Boolean = pkg in MESSAGING_PACKAGES
}
