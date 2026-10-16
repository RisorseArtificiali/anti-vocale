package com.antivocale.app.util

/**
 * TASK-809 (GH #146): a one-way micro-detector for the transcript-language
 * advisory. Answers ONE question, "does this transcript's language differ
 * from the user's device language?", well enough to raise a hint, never
 * well enough to act on (the advisory is the whole product: no silent
 * pinning, the TASK-462 'silent lie' class).
 *
 * Scope and limits, deliberately narrow (the resolved design):
 * - Non-Latin scripts by Unicode block: one block check per script, no
 *   disambiguation within a script (zh/ja share CJK; the answer only
 *   needs "different from the device language", which zh-vs-ja still is
 *   when the device is fr).
 * - Latin languages by stopword frequency over the seven the advisory
 *   can name meaningfully (en/fr/it/es/de/pt/nl + the stopword lists
 *   double for audio the models actually transcribe). Clear-margin gate:
 *   the top language must have >= [MIN_HITS] hits and >= 2x the runner-up,
 *   so mixed or short text yields null (no hint) rather than a guess.
 * - >= [MIN_CHARS] letters of transcript before any opinion.
 */
object TranscriptLanguageHeuristic {

    const val MIN_CHARS = 40
    const val MIN_HITS = 3

    /** Language statistics are stable long before this; the cap keeps the
     *  one-pass cost off the UI thread for multi-MB transcripts. */
    const val MAX_SAMPLE_CHARS = 4_000

    // Top-frequency function words per language; lowercase, no accents
    // stripped beyond NFC (the detector lowercases and compares as-is:
    // French "est" with accents on neighbors is unaffected).
    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "is", "was", "you", "that", "it", "for", "with", "have", "this"),
        "fr" to setOf("le", "la", "les", "est", "et", "que", "pas", "nous", "vous", "pour", "dans"),
        "it" to setOf("il", "che", "non", "di", "sono", "per", "con", "una", "come", "ma", "anche"),
        "es" to setOf("el", "que", "no", "de", "es", "por", "con", "para", "una", "como", "pero"),
        "de" to setOf("der", "die", "das", "ist", "und", "nicht", "ich", "mit", "für", "auch", "aber"),
        "pt" to setOf("que", "não", "de", "com", "para", "uma", "como", "mas", "você", "está", "eu"),
        "nl" to setOf("de", "het", "een", "en", "is", "niet", "van", "ik", "met", "maar", "ook"),
    )

    /** Latin-script-only detection via stopwords; null on no clear margin. */
    private fun detectLatin(words: List<String>): String? {
        val scores = mutableMapOf<String, Int>()
        for (word in words) {
            for ((lang, list) in STOPWORDS) {
                if (word in list) scores.merge(lang, 1, Int::plus)
            }
        }
        if (scores.isEmpty()) return null
        val sorted = scores.entries.sortedByDescending { it.value }
        val top = sorted.first()
        if (top.value < MIN_HITS) return null
        val runnerUp = sorted.getOrNull(1)?.value ?: 0
        if (top.value < 2 * runnerUp) return null
        return top.key
    }

    /** One compiled split, consistent with the top-level STOPWORDS val. */
    private val WORD_SPLIT = Regex("[^\\p{L}']+")

    /**
     * The script families the block arm maps to, keyed by every ISO code
     * they cover that the app or its models plausibly serve (the block
     * arm collapses families deliberately; see the class KDoc).
     */
    private val SCRIPT_FAMILY = mapOf(
        "ru" to "cyrillic", "uk" to "cyrillic", "be" to "cyrillic", "bg" to "cyrillic",
        "ar" to "arabic", "fa" to "arabic", "ur" to "arabic",
        "iw" to "hebrew", "he" to "hebrew",
        "hi" to "devanagari",
        "zh" to "cjk", "ja" to "cjk",
        "ko" to "hangul",
        "th" to "thai",
    )

    /** The block family a code collapses to, or null for Latin script. */
    fun scriptFamilyOf(code: String): String? = SCRIPT_FAMILY[code]

    /**
     * Whether the heuristic can honestly assert "guessed is NOT device":
     * different script families are always distinguishable; within one
     * script, only when BOTH codes are in the stopword map (for a device
     * language the map cannot represent, the answer must stay "cannot
     * tell" - review F4: Romanian text manufacturing a confident "fr"
     * from stray shared stopwords).
     */
    fun canDistinguish(guessed: String, device: String): Boolean {
        val guessedFamily = scriptFamilyOf(guessed)
        val deviceFamily = scriptFamilyOf(device)
        if (guessedFamily != null || deviceFamily != null) {
            return guessedFamily != deviceFamily
        }
        return guessed in STOPWORDS && device in STOPWORDS
    }

    /**
     * The transcript's language as an ISO 639-1 code, or null when the
     * text is too short or gives no clear margin. Call on the raw result
     * text; safe on any input.
     */
    fun detect(text: String): String? {
        val text = text.take(MAX_SAMPLE_CHARS)
        val letters = text.count { it.isLetter() }
        if (letters < MIN_CHARS) return null

        // Non-Latin scripts by block, strongest signal first. One block
        // hit wins immediately: a Cyrillic word in a Latin transcript is
        // rare, a Latin word in a Cyrillic transcript is common enough
        // that we check the blocks before counting anything.
        var cyrillic = 0
        var arabic = 0
        var hebrew = 0
        var devanagari = 0
        var cjk = 0
        var hangul = 0
        var thai = 0
        var latin = 0
        for (c in text) {
            if (!c.isLetter()) continue
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.CYRILLIC -> cyrillic++
                Character.UnicodeBlock.ARABIC -> arabic++
                Character.UnicodeBlock.HEBREW -> hebrew++
                Character.UnicodeBlock.DEVANAGARI -> devanagari++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA -> cjk++
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO -> hangul++
                Character.UnicodeBlock.THAI -> thai++
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> latin++
                else -> {}
            }
        }
        // A script wins at a tenth of the letters AND over the Latin
        // count (a 10% loanword in a Latin transcript never flips the
        // verdict - review F4: the one-tenth bound alone let a single
        // Russian word flip a 70-letter English transcript).
        fun wins(count: Int) = count * 10 >= letters && count > latin
        val script = when {
            wins(cyrillic) -> "ru" // uk/be also map here: block only
            wins(arabic) -> "ar"   // fa/ur too: the advisory only
            wins(hebrew) -> "iw"   // needs "different from device"
            wins(devanagari) -> "hi"
            wins(cjk) -> "zh"      // ja included
            wins(hangul) -> "ko"
            wins(thai) -> "th"
            else -> null
        }
        if (script != null) return script

        val words = text.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }
        return detectLatin(words)
    }
}
