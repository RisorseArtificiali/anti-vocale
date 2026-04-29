package com.antivocale.app.transcription

object LanguageFlags {

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
}
