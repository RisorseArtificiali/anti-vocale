package com.antivocale.app.transcription

import com.antivocale.app.R

/**
 * Language metadata for the model filter feature.
 *
 * Contains per-backend language support sets (ISO 639-1 codes)
 * and a curated list of filter entries for the UI dropdown.
 */
object Language {

    data class Entry(val code: String, val nameResId: Int)

    /** Languages shown in the filter dropdown (~30 most useful). */
    val FILTER_ENTRIES: List<Entry> = listOf(
        Entry("af", R.string.lang_afrikaans),
        Entry("ar", R.string.lang_arabic),
        Entry("az", R.string.lang_azerbaijani),
        Entry("be", R.string.lang_belarusian),
        Entry("bg", R.string.lang_bulgarian),
        Entry("bn", R.string.lang_bengali),
        Entry("ca", R.string.lang_catalan),
        Entry("cs", R.string.lang_czech),
        Entry("cy", R.string.lang_welsh),
        Entry("da", R.string.lang_danish),
        Entry("de", R.string.lang_german),
        Entry("el", R.string.lang_greek),
        Entry("en", R.string.lang_english),
        Entry("es", R.string.lang_spanish),
        Entry("et", R.string.lang_estonian),
        Entry("eu", R.string.lang_basque),
        Entry("fa", R.string.lang_persian),
        Entry("fi", R.string.lang_finnish),
        Entry("fr", R.string.lang_french),
        Entry("gl", R.string.lang_galician),
        Entry("he", R.string.lang_hebrew),
        Entry("hi", R.string.lang_hindi),
        Entry("hr", R.string.lang_croatian),
        Entry("hu", R.string.lang_hungarian),
        Entry("id", R.string.lang_indonesian),
        Entry("it", R.string.lang_italian),
        Entry("ja", R.string.lang_japanese),
        Entry("ka", R.string.lang_georgian),
        Entry("ko", R.string.lang_korean),
        Entry("lt", R.string.lang_lithuanian),
        Entry("lv", R.string.lang_latvian),
        Entry("mk", R.string.lang_macedonian),
        Entry("ms", R.string.lang_malay),
        Entry("nl", R.string.lang_dutch),
        Entry("no", R.string.lang_norwegian),
        Entry("pl", R.string.lang_polish),
        Entry("pt", R.string.lang_portuguese),
        Entry("ro", R.string.lang_romanian),
        Entry("ru", R.string.lang_russian),
        Entry("sk", R.string.lang_slovak),
        Entry("sl", R.string.lang_slovenian),
        Entry("sq", R.string.lang_albanian),
        Entry("sr", R.string.lang_serbian),
        Entry("sv", R.string.lang_swedish),
        Entry("sw", R.string.lang_swahili),
        Entry("ta", R.string.lang_tamil),
        Entry("th", R.string.lang_thai),
        Entry("tr", R.string.lang_turkish),
        Entry("uk", R.string.lang_ukrainian),
        Entry("ur", R.string.lang_urdu),
        Entry("uz", R.string.lang_uzbek),
        Entry("vi", R.string.lang_vietnamese),
        Entry("zh", R.string.lang_chinese),
    )

    // ==================== Per-backend language sets ====================

    /** Whisper Small/Turbo/Medium: 99 languages (OpenAI Whisper model card). */
    val WHISPER_MULTILINGUAL: Set<String> = setOf(
        "af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo",
        "br", "bs", "ca", "cs", "cy", "da", "de", "el", "en", "eo",
        "es", "et", "eu", "fa", "fi", "fo", "fr", "gl", "gu", "ha",
        "haw", "he", "hi", "hr", "ht", "hu", "hy", "id", "is", "it",
        "ja", "jw", "ka", "kk", "km", "kn", "ko", "la", "lb", "ln",
        "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms",
        "mt", "my", "ne", "nl", "nn", "no", "oc", "pa", "pl", "ps",
        "pt", "ro", "ru", "sa", "sd", "si", "sk", "sl", "sn", "so",
        "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "th", "tk",
        "tl", "tr", "tt", "ug", "uk", "ur", "uz", "vi", "yi", "yo",
        "zh",
    )

    /** Whisper Distil-Large-V3-IT: Italian only. */
    val WHISPER_DISTIL_IT: Set<String> = setOf("it")

    /** Qwen3-ASR 0.6B: 52 languages (Qwen model card). */
    val QWEN3_ASR: Set<String> = setOf(
        "af", "am", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs",
        "cy", "da", "de", "el", "en", "es", "et", "eu", "fa", "fi",
        "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "it", "ja",
        "jv", "ka", "kk", "ko", "lt", "lv", "mk", "ml", "ms", "nl",
        "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv",
        "sw", "ta", "th", "tr", "uk", "ur", "uz", "vi", "zh",
    )

    /** Parakeet TDT: 25 languages (NVIDIA model card). */
    val PARAKEET: Set<String> = setOf(
        "de", "en", "es", "fr", "it", "nl", "pl", "pt", "ro", "ru",
        "sv", "tr", "uk", "cs", "bg", "hr", "da", "fi", "el", "hu",
        "no", "sk", "sl", "et", "lv",
    )

    /** Gemma models: broad multilingual (140+ languages). */
    val GEMMA: Set<String> = setOf(
        "af", "am", "ar", "as", "az", "be", "bg", "bn", "bs", "ca",
        "cs", "cy", "da", "de", "el", "en", "eo", "es", "et", "eu",
        "fa", "fi", "fo", "fr", "ga", "gd", "gl", "gu", "ha", "he",
        "hi", "hr", "ht", "hu", "hy", "id", "ig", "is", "it", "ja",
        "ka", "kk", "km", "kn", "ko", "la", "lb", "lo", "lt", "lv",
        "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt", "my", "ne",
        "nl", "nn", "no", "ny", "oc", "pa", "pl", "ps", "pt", "ro",
        "ru", "rw", "sa", "sd", "si", "sk", "sl", "sn", "so", "sq",
        "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "ti",
        "tk", "tl", "tr", "tt", "ug", "uk", "ur", "uz", "vi", "wo",
        "xh", "yi", "yo", "zh", "zu",
    )

    /** Nemotron 3.5 streaming multilingual — languages the model conditions on
     *  (derived from its ONNX prompt_dictionary; see docs/sherpa-onnx-multilingual-validation.ipynb).
     *  Restricted to languages that have localized display names in [FILTER_ENTRIES]. */
    val NEMOTRON: Set<String> = setOf(
        "en", "es", "zh", "hi", "ar", "fr", "de", "ja", "ru", "pt",
        "ko", "it", "nl", "pl", "tr", "uk", "ro", "el", "cs", "hu",
        "sv", "da", "fi", "no", "sk", "hr", "bg", "lt", "th", "vi",
        "id", "ms", "bn", "ur", "fa", "ta", "sw", "af", "et", "lv",
        "sl", "he", "az", "ka", "uz",
    )
}
