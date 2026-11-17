package com.antivocale.app.data

/**
 * Best-effort caller attribution for the Scheduled folder watch (GH #189):
 * many call recorders put the caller's number or contact name in the file
 * name, so a watched folder full of recordings can carry the caller onto the
 * transcribed row through the same [com.antivocale.app.service.InferenceService]
 * sender-name path the WhatsApp pipeline uses (TASK-736).
 *
 * The public contract stated on GH #189 applies here: best effort, ONLY file
 * names matching a known recorder format get a label, anything unrecognized
 * transcribes with no attribution, and nothing is parsed out of the audio
 * itself. Every matcher below must be anchored to evidence that the file is a
 * CALL recording (a documented recorder template or a call-context keyword):
 * a bare phone-number-looking run is not enough, or voice memos named
 * `Record_<timestamp>` would get attributed to a caller. A wrong caller label
 * is the worst failure here, so the matchers stay conservative: when in
 * doubt, no label.
 */
object CallRecordingFilenames {

    /** Tokens that mean "no caller" rather than a caller; the row stays unlabeled. */
    private val ANONYMOUS = setOf(
        "unknown", "unknownnumber", "unknowncaller", "private", "privatenumber",
        "privatecaller", "anonymous", "sconosciuto", "numerosconosciuto", "privato",
        "nocallerid", "withheld", "numeronascosto", "anonimo")

    /** Call-context keywords; case-insensitive, matched on whole name tokens. */
    private val CALL_KEYWORDS = listOf("call", "chiamata")

    /** Non-alphanumeric runs, for tokenizing the name for keyword matching. */
    private val NON_WORD = Regex("""[^A-Za-z]+""")

    /**
     * BCR date head: YYYYMMDD[_T]HHMMSS, optionally fractional seconds and a
     * timezone. Only the separators BCR's template actually emits ('_' and
     * the ISO 'T'): a '-' here would admit dash-joined heads the segment
     * classifier below cannot strip, leaking the timestamp into the label.
     */
    private val BCR_DATE_HEAD = Regex("""\d{8}[_T]\d{6}(?:\.\d+)?(?:Z|[+-]\d{4})?(_.*)?""")

    /** BCR head segments: the date (possibly T-joined), the time, the direction markers, sim slots. */
    private val BCR_DATE = Regex("""\d{8}""")
    private val BCR_DATE_TIME_JOINED = Regex("""\d{8}T\d{6}(\.\d+)?(Z|[+-]\d{4})?""")
    private val BCR_TIME = Regex("""\d{6}(\.\d+)?(Z|[+-]\d{4})?""")
    private val BCR_SIM = Regex("""sim\d+""")
    private val BCR_DIRECTIONS = setOf("in", "out", "conference")

    /** `Call from/with` only: `Call to` collides with podcast and track titles. */
    private val CALL_FROM_PREFIX = Regex("""(?i)^call[\s_-]*(?:from|with)[\s_-]+(.+)$""")

    /**
     * Date tails for the prefix matcher's cut, anchored to a 19/20 century
     * head and kept OUT of number tokens: the lookbehind refuses a position
     * preceded by a digit or a dash (so "0461-12..." inside a formatted
     * phone number never cuts), while '_', '(', and ' ' before a real date
     * are fine.
     */
    private val DATE_TAILS = listOf(
        Regex("""(?<![\d-])[()\[]?(?:19|20)\d{2}[-_.]\d{2}"""),
        Regex("""(?<![\d-])\d{1,2}[-_.]\d{2}[-_.](?:19|20)\d{2}"""),
        // Lookarounds, not \b: '_' is a word character, so \b never fires
        // between "rossi_20240101" and its digits.
        Regex("""(?<![\d-])(?:19|20)\d{6}(?!\d)"""))

    private val PUNCTUATION_RUN = Regex("""[\s_.-]+""")
    private val NUMBER_SHAPED = Regex("""\+?\d[\d\s().-]{4,24}\d""")

    /**
     * Returns the caller label for a call-recorder file name, or null when the
     * name matches no known format (the row then has no attribution).
     */
    fun callerFromFileName(fileName: String): String? {
        // The folder-watch pipeline's own audio gate, not a private copy of
        // the extension vocabulary: the planner's sets are composed so the
        // surfaces cannot drift on what counts as input.
        if (!FolderScanPlanner.isAudioName(fileName)) return null
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val stem = fileName.substring(0, dot)

        callerFromBcrTemplate(stem)?.let { return it }
        callerFromCallFromPrefix(stem)?.let { return it }
        return callerFromKeywordAndNumber(stem)
    }

    /**
     * BCR (chenxiaolong's Basic Call Recorder) documents its default template
     * as `{date}[_{direction}|][_sim{sim_slot}|][_{phone_number}|][_[{contact_name}|{caller_name}|{call_log_name}]|]`,
     * e.g. `20230414_215701_in_sim0_+15555550123_John_Doe.m4a` or plain
     * `20230414_215701.m4a`. The template reaches its caller segment only
     * through a direction/sim marker or a phone-number token, so a bare
     * name-shaped tail (a memo like `20240101_123456_groceries.m4a`) gets NO
     * label: without that anchor the name proves nothing about a call.
     */
    private fun callerFromBcrTemplate(stem: String): String? {
        // BCR joins template segments with '_', so the split stays on '_'
        // alone: a '-' split would tear "+39..." numbers and "-0400"
        // timezones apart.
        if (!stem.matches(BCR_DATE_HEAD)) return null
        val segments = stem.split('_').filter { it.isNotBlank() }
        if (segments.size <= 1) return null
        var sawMarker = false
        val rest = mutableListOf<String>()
        for (segment in segments) {
            val isHead = segment.matches(BCR_DATE) || segment.matches(BCR_DATE_TIME_JOINED) ||
                segment.matches(BCR_TIME)
            val isMarker = segment in BCR_DIRECTIONS || segment.matches(BCR_SIM)
            if (isMarker) sawMarker = true
            if (!isHead && !isMarker) rest += segment
        }
        if (rest.isEmpty()) return null
        // The template's caller arrives after a marker or is itself the
        // phone-number segment; a name with neither anchor is unproven.
        // Number-shaped means '+' or a real digit run (5+): "v2"/"note2"
        // memo suffixes carry digits but prove nothing about a caller.
        val first = rest.first()
        val anchored = sawMarker || first.startsWith("+") || first.count { it.isDigit() } >= 5
        if (!anchored) return null
        val caller = normalizeCaller(rest.joinToString(" ")) ?: return null
        // A tail that is nothing but a bare date run is metadata, not a
        // caller (a real bare phone number, 7..11 digits, stays eligible:
        // the sibling matcher accepts the same shape).
        val digitsOnly = caller.filter { it.isDigit() }
        if (caller.length == digitsOnly.length && isYearLikeDigits(digitsOnly)) return null
        return caller.takeIf { isPlausibleCaller(it) }
    }

    /**
     * The `Call from <X>` / `Call with <X>` family (Cube ACR and similar):
     * the caller runs from the prefix to the first date-like tail. The cut
     * is positional on the raw remainder, not token-wise, because phone
     * numbers and dates share '-' as a separator.
     */
    private fun callerFromCallFromPrefix(stem: String): String? {
        val m = CALL_FROM_PREFIX.find(stem) ?: return null
        val remainder = m.groupValues[1]
        val tailCut = DATE_TAILS
            .mapNotNull { it.find(remainder)?.range?.first }
            .minOrNull() ?: remainder.length
        return normalizeCaller(remainder.substring(0, tailCut))?.takeIf { isPlausibleCaller(it) }
    }

    /**
     * The generic rule for the undocumented OEM formats (Samsung, Pixel
     * Dialer, Xiaomi call_rec, ...): a call-context keyword somewhere in the
     * name PLUS a phone-number-shaped token. The keyword requirement is what
     * keeps voice memos (`Record_<timestamp>`) and generic recordings from
     * being attributed to a caller; the token guards below are what keep a
     * separated `YYYYMMDD_HHMMSS` pair (no caller in the name at all) from
     * being read as a number.
     */
    private fun callerFromKeywordAndNumber(stem: String): String? {
        // Cheap substring pre-gate before tokenizing (the reject path for
        // most folder audio): no token can start with a keyword the whole
        // stem does not contain.
        if (CALL_KEYWORDS.none { stem.contains(it, ignoreCase = true) }) return null
        // Token-boundary keyword match, not substring: "recall" must not
        // satisfy "call" (the review's false-attribution class).
        val tokens = NON_WORD.split(stem).filter { it.isNotBlank() }.map { it.lowercase() }
        if (CALL_KEYWORDS.none { keyword -> tokens.any { it.startsWith(keyword) } }) {
            return null
        }
        val number = NUMBER_SHAPED.findAll(stem)
            .map { it.value }
            .firstOrNull { token ->
                val digitsOnly = token.filter { it.isDigit() }
                val digits = digitsOnly.length
                // A leading '+' says number explicitly (E.164 +39 mobiles are
                // 12 digits). A bare digit run must look like a phone, never
                // a date or clock: 7..11 digits, 12..15 only with '+', and
                // not 8 digits with a 19/20 head ("2024-01-01").
                if (token.startsWith("+")) {
                    digits in 7..15
                } else {
                    digits in 7..11 && !isYearLikeDigits(digitsOnly)
                }
            } ?: return null
        return normalizeCaller(number)
    }

    /** Underscore-joined tokens become spaces; outer separators go.
     *  Brackets are NOT trimmed here (a balanced parenthetical is part of
     *  the name): balanceParens alone drops unmatched ones. */
    private fun normalizeCaller(raw: String): String? {
        val cleaned = raw.replace('_', ' ').trim(' ', '-', '.')
        return balanceParens(cleaned).takeIf { it.isNotBlank() }
    }

    /** Drops bracket characters whose partner a cut or the recorder's own
     *  naming removed, then trims the seam. Type-blind by design (any
     *  closer closes any opener), so unmatchedness is a depth property:
     *  left-to-right drops orphan closers, right-to-left drops orphan
     *  openers. */
    private fun balanceParens(label: String): String {
        val sb = StringBuilder(label.length)
        var depth = 0
        for (c in label) when (c) {
            '(', '[' -> { depth++; sb.append(c) }
            ')', ']' -> if (depth > 0) { depth--; sb.append(c) }
            else -> sb.append(c)
        }
        depth = 0
        for (i in sb.length - 1 downTo 0) when (sb[i]) {
            ')', ']' -> depth++
            '(', '[' -> if (depth > 0) depth-- else sb.deleteCharAt(i)
        }
        return sb.toString().trim(' ', '-', '.')
    }

    /** A caller label must not be the recorder's own "no caller" marker. */
    private fun isPlausibleCaller(label: String): Boolean =
        label.lowercase().replace(PUNCTUATION_RUN, "") !in ANONYMOUS

    /** The one date-shape both digit guards use (the sibling-matchers'
     *  parity their comments assert lives here). */
    private fun isYearLikeDigits(digits: String): Boolean =
        digits.length == 8 && (digits.startsWith("19") || digits.startsWith("20"))
}
