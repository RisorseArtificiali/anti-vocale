package com.antivocale.app.transcription

/**
 * Language metadata for the model filter feature.
 *
 * Contains per-backend language support sets (ISO 639-1 codes)
 * and a curated list of filter codes for the UI dropdown. Display names come
 * from the platform ICU data via [com.antivocale.app.util.LanguageNames].
 */
object Language {

    /** Languages shown in the filter dropdown (~50 most useful). */
    val FILTER_ENTRIES: List<String> = listOf(
        "af", "ar", "az", "be", "bg", "bn", "ca", "cs", "cy", "da",
        "de", "el", "en", "es", "et", "eu", "fa", "fi", "fr", "gl",
        "gu",
        "he", "hi", "hr", "hu", "id", "it", "ja", "ka", "ko", "lt",
        "lv", "mk", "ms", "mr", "nl", "no", "pl", "pt", "ro", "ru", "sk",
        "sl", "sq", "sr", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur",
        "uz", "vi", "zh",
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

    /** GigaAM v3: Russian only (E2E RNNT, Sber). */
    val GIGAAM: Set<String> = setOf("ru")

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
     *  (derived from its ONNX prompt_dictionary; see docs/sherpa-onnx-multilingual-validation.ipynb). */
    val NEMOTRON: Set<String> = setOf(
        "en", "es", "zh", "hi", "ar", "fr", "de", "ja", "ru", "pt",
        "ko", "it", "nl", "pl", "tr", "uk", "ro", "el", "cs", "hu",
        "sv", "da", "fi", "no", "sk", "hr", "bg", "lt", "th", "vi",
        "id", "ms", "bn", "ur", "fa", "ta", "sw", "af", "et", "lv",
        "sl", "he", "az", "ka", "uz",
    )
}
