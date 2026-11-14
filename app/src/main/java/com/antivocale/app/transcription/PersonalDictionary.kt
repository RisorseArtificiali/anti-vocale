package com.antivocale.app.transcription

/**
 * TASK-994: the personal dictionary (the TASK-858 keyword-biasing terms
 * generalized from the whistle engine to every surface that can honor a
 * vocabulary today). ONE parsing home for the newline-separated terms.
 *
 * The honest per-engine matrix (probed at the sherpa-onnx v2.0.1 pin,
 * af2d8d81: source read + desktop probe on the stock Parakeet TDT):
 * - Whistle: native biasing (TASK-858, shipped).
 * - Qwen3-ASR: hotwords ride the chat-template system prompt per stream
 *   (OfflineStream.setOption("hotwords", csv)); wired in SherpaBackend's
 *   offline decode, the same request-time shape as Whistle. Desktop probe:
 *   biased misspellings flip the decode (the mechanism is live).
 * - Gemma passes (polish, summary): the dictionary rides the instruction
 *   prompt via [withVocabularyLine]. The CONSERVATIVE polish mode pins its
 *   fenced prompt and deliberately ignores the dictionary (a suffix could
 *   break the fences the token validation enforces); the Gemma AUDIO
 *   prompt (the LLM transcription backend) is unprobed and not wired.
 * - Transducer sherpa families (Parakeet TDT, GigaAM v3): hotword biasing
 *   is WIRED in SherpaBackend.initOffline (construction-time, beam-gated;
 *   the dictionary applies at the next model load). The bpe_vocab artifact
 *   is self-hosted on the pantinor mirror and the CATALOG PIN is HELD
 *   until a migration exists for existing installs (the pin alone would
 *   fail their load gate with a full re-download heal; see the research
 *   note for the extraction chain).
 * - Whisper/CTC/SenseVoice/Canary: no hotwords wiring in the sherpa impls
 *   at this pin; do not promise them in UI copy.
 */
object PersonalDictionary {

    /**
     * Safety nets ABOVE the pref's 500-char save cap ([PreferencesManager.PROMPT_CAP]
     * bounds keywordBiasing at save time): a VALIDLY STORED dictionary can
     * never be truncated. MAX_CHARS covers the JOIN worst case: 250 one-char
     * terms store as 499 chars and join as 748 (term + ", " per gap), so 750
     * keeps Whistle ([terms]) and every rider consumer ([promptTerms]) on
     * the SAME term set. The caps only catch writes that bypass the save
     * cap (the SPI writing a longer raw value) and future pref-cap changes.
     */
    const val MAX_TERMS = 200
    const val MAX_CHARS = 750

    /**
     * The lenient term parse every consumer shares (Whistle's TASK-858
     * semantics): newline OR comma separators, trimmed, empties dropped.
     * Commas inside a term are meaningless (the native boundary joins with
     * a newline; a pasted comma-separated list must not reach any engine
     * as one giant keyword).
     */
    fun terms(rawTerms: String): List<String> =
        rawTerms.split('\n', ',')
            .map(String::trim)
            .filter(String::isNotEmpty)

    /**
     * The terms joined for prompt context, or null when the dictionary is
     * empty (the caller renders the unmodified instruction). Terms are
     * never CUT: a truncated spelling would teach the model the exact
     * wrong thing the dictionary exists to prevent, so the join stops
     * before the first term that would overflow [MAX_CHARS].
     */
    fun promptTerms(rawTerms: String, maxChars: Int = MAX_CHARS): String? {
        val joined = buildString {
            for (term in terms(rawTerms).take(MAX_TERMS)) {
                val piece = if (isEmpty()) term else ", $term"
                if (length + piece.length > maxChars) break
                append(piece)
            }
        }
        return joined.ifEmpty { null }
    }

    /**
     * TASK-994 review: the hotwords-file encoding (one term per line), the
     * inverse of [promptTerms]'s ", " join; ONE home beside it so the two
     * backends' file formats cannot drift.
     */
    fun toHotwordsFileLines(csv: String): String =
        csv.split(", ").joinToString("\n") { it.trim() }

    /**
     * Appends the localized vocabulary line to an instruction when the
     * dictionary has terms. The [lineFor] lambda receives the joined terms
     * and returns the caller's locale-resolved template (this object stays
     * Android-free and JVM-testable).
     */

    fun withVocabularyLine(
        instruction: String,
        rawTerms: String,
        lineFor: (String) -> String,
    ): String {
        val terms = promptTerms(rawTerms) ?: return instruction
        return instruction + "\n\n" + lineFor(terms)
    }
}
