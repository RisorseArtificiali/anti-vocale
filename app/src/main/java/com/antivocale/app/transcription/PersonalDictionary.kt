package com.antivocale.app.transcription

/**
 * TASK-994: the personal dictionary (the TASK-858 keyword-biasing terms
 * generalized from the whistle engine to every surface that can honor a
 * vocabulary today). ONE parsing home for the newline-separated terms.
 *
 * The honest per-engine matrix (probed at the sherpa-onnx v2.0.1 pin,
 * af2d8d81: source read + desktop probe on the stock Parakeet TDT):
 * - Whistle: native biasing (TASK-858, shipped).
 * - Gemma passes (polish, summary): the dictionary rides the instruction
 *   prompt via [withVocabularyLine] - the one generalization available
 *   today. The CONSERVATIVE polish mode pins its fenced prompt and
 *   deliberately ignores the dictionary (a suffix could break the fences
 *   the token validation enforces); the Gemma AUDIO prompt (the LLM
 *   transcription backend) is unprobed and not wired - do not promise it.
 * - sherpa ASR families: hotword biasing is wired for transducer-family
 *   decodes in modified_beam_search, but the encoding needs a bpe_vocab
 *   artifact the model exports do not ship: without it, Latin terms are
 *   dropped by the token table (probe: output byte-identical with and
 *   without hotwords) and a modeling_unit of "bpe" with no vocab file
 *   SEGFAULTS the process (probe: rc=-11). Whisper/Qwen/CTC/SenseVoice/
 *   Canary impls have no hotwords wiring at all. Blocked upstream until
 *   exports ship bpe_vocab; do not promise it in UI copy.
 */
object PersonalDictionary {

    /**
     * Safety nets ABOVE the pref's 500-char save cap ([PreferencesManager.PROMPT_CAP]
     * bounds keywordBiasing at save time): a VALIDLY STORED dictionary can
     * never be truncated (worst case ~167 two-char terms join under 600
     * chars), so Whistle and the Gemma rider always see the SAME term set.
     * The caps only catch writes that bypass the save cap (the SPI writing
     * a longer raw value) and future pref-cap changes.
     */
    const val MAX_TERMS = 200
    const val MAX_CHARS = 600

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
    fun promptTerms(rawTerms: String): String? {
        val joined = buildString {
            for (term in terms(rawTerms).take(MAX_TERMS)) {
                val piece = if (isEmpty()) term else ", $term"
                if (length + piece.length > MAX_CHARS) break
                append(piece)
            }
        }
        return joined.ifEmpty { null }
    }

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
