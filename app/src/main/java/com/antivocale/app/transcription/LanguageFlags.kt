package com.antivocale.app.transcription

/**
 * The ONE language-to-flag-emoji table. Two consumers:
 *  - the model-info language chips (TASK-790 lineage): [getFlag] per code;
 *  - TASK-796 model-card titles: [titled] prefixes a title with the flags
 *    of a monolingual/few-language set (at most [MAX_TITLE_FLAG_LANGUAGES]
 *    distinct codes; multilingual models carry none). Unmapped codes
 *    contribute nothing, so a card title never invents a flag.
 */
object LanguageFlags {

    /** The "few languages" cutoff for title FLAGS (maintainer convention). */
    const val MAX_TITLE_FLAG_LANGUAGES = 3

    /**
     * TASK-820 follow-up (maintainer, 2026-10-13): models with MORE than
     * [MAX_TITLE_FLAG_LANGUAGES] languages carry the earth emoji instead,
     * so every card keeps a language signal and the symbol itself says
     * "multilingual" rather than nothing saying anything. One constant:
     * swap to another glyph (e.g. the meridian globe) is a one-line change.
     */
    const val MULTILINGUAL_PREFIX = "\uD83C\uDF0D "

    /**
     * The community catalog's literal "speaks many languages" marker
     * (declared in place of language codes on entries like Whisper Tiny
     * multilingual).
     */
    const val MULTILINGUAL_MARKER = "multilingual"

    private val specialMappings = mapOf(
        "af" to "ZA", "am" to "ET", "ar" to "SA", "as" to "IN", "az" to "AZ",
        "ba" to "RU", "be" to "BY", "bg" to "BG", "bn" to "BD", "bo" to "CN",
        "br" to "FR", "bs" to "BA", "ca" to "ES", "cs" to "CZ", "cy" to "GB",
        "da" to "DK", "de" to "DE", "el" to "GR", "en" to "GB", "eo" to "EU",
        "es" to "ES", "et" to "EE", "eu" to "ES", "fa" to "IR", "fi" to "FI",
        "fo" to "FO", "fr" to "FR", "ga" to "IE", "gd" to "GB", "gl" to "ES",
        "gu" to "IN", "ha" to "NG", "haw" to "US", "he" to "IL", "hi" to "IN",
        "hr" to "HR", "ht" to "HT", "hu" to "HU", "hy" to "AM", "id" to "ID",
        "ig" to "NG", "is" to "IS", "it" to "IT", "ja" to "JP", "jv" to "ID",
        "jw" to "ID", "ka" to "GE", "kk" to "KZ", "km" to "KH", "kn" to "IN",
        "ko" to "KR", "la" to "VA", "lb" to "LU", "ln" to "CD", "lo" to "LA",
        "lt" to "LT", "lv" to "LV", "mg" to "MG", "mi" to "NZ", "mk" to "MK",
        "ml" to "IN", "mn" to "MN", "mr" to "IN", "ms" to "MY", "mt" to "MT",
        "my" to "MM", "ne" to "NP", "nl" to "NL", "nn" to "NO", "no" to "NO",
        "ny" to "MW", "oc" to "FR", "pa" to "IN", "pl" to "PL", "ps" to "AF",
        "pt" to "PT", "ro" to "RO", "ru" to "RU", "rw" to "RW", "sa" to "IN",
        "sd" to "PK", "si" to "LK", "sk" to "SK", "sl" to "SI", "sn" to "ZW",
        "so" to "SO", "sq" to "AL", "sr" to "RS", "st" to "LS", "su" to "ID",
        "sv" to "SE", "sw" to "TZ", "ta" to "IN", "te" to "IN", "tg" to "TJ",
        "th" to "TH", "ti" to "ER", "tk" to "TM", "tl" to "PH", "tr" to "TR",
        "tt" to "RU", "ug" to "CN", "uk" to "UA", "ur" to "PK", "uz" to "UZ",
        "vi" to "VN", "wo" to "SN", "xh" to "ZA", "yi" to "IL", "yo" to "NG",
        "zh" to "CN", "zu" to "ZA",
        // ISO 639-3 codes the community catalog declares: Swiss German
        // maps to the Swiss flag (the de+gsw bilingual entry shows both).
        "gsw" to "CH",
    )

    private fun countryCodeToFlag(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val c1 = countryCode[0].uppercaseChar()
        val c2 = countryCode[1].uppercaseChar()
        if (!c1.isLetter() || !c2.isLetter()) return ""
        return String(intArrayOf(
            0x1F1E6 + (c1 - 'A'),
            0x1F1E6 + (c2 - 'A')
        ), 0, 2)
    }

    fun getFlag(languageCode: String): String {
        val countryCode = specialMappings[languageCode] ?: return ""
        return countryCodeToFlag(countryCode)
    }

    /**
     * TASK-796: a model-card title prefixed with its languages' flags (and
     * a trailing space), or the title unchanged when the set is empty, too
     * large, or entirely unmapped.
     */
    fun titled(title: String, languageCodes: Collection<String>): String {
        val distinct = languageCodes.distinct()
        if (distinct.isEmpty()) return title
        // The community catalog declares the LITERAL marker "multilingual"
        // on entries that speak many languages (Whisper Tiny multilingual):
        // that IS the earth-prefix semantic, not an unmappable typo.
        if (distinct.size > MAX_TITLE_FLAG_LANGUAGES || distinct.contains(MULTILINGUAL_MARKER)) {
            return MULTILINGUAL_PREFIX + title
        }
        // Conservative: EVERY code must map. A partial prefix would claim a
        // language set the model does not have (review round: a mixed
        // [unknown, it] set showing only the Italian flag).
        val flags = distinct.map { getFlag(it) }
        if (flags.any { it.isEmpty() }) return title
        return flags.joinToString("") + " " + title
    }
}
