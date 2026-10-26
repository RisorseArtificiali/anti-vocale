package com.antivocale.app.transcription

/**
 * TASK-910 (maintainer request, inspired by Gboard Rambler's experimental
 * STT): a long pause between sentences is the END OF A PARAGRAPH. When the
 * gap between two consecutive sentence cues exceeds [PARAGRAPH_GAP_MS], the
 * transcript text gains a double newline at that boundary; ordinary sentence
 * pauses stay single spaces. Long dictations read as paragraphs instead of
 * one wall of text.
 *
 * The threshold: Google publishes no number for Rambler (searched 2026-10-26;
 * the ~3s Gboard figure floating around is its stop-listening timeout, not a
 * break threshold), so OUR own measured constants are the basis:
 * [SentenceCueBuilder.PAUSE_SPLIT_MS] (700ms) already splits a cue at a
 * sentence pause, and ResultPlayback's SKIPPABLE_GAP_MS (1000ms) is the
 * player's skip bar. 1500ms sits clearly above both: no ordinary sentence
 * pause breaks, and what breaks is a pause a reader really feels as a new
 * paragraph. Tunable the moment real use says otherwise.
 *
 * Honest degradation: the rebuild only happens when the cues' texts
 * whitespace-normalize EXACTLY to the recognizer's text ([SentenceCueBuilder]
 * normalizes cue texts to character alignment with the recognizer output, so
 * this holds on the good paths). On any mismatch the text returns UNCHANGED:
 * a transcript without paragraph breaks is fine; a corrupted one never is.
 */
object ParagraphBreaks {

    /**
     * The pause that ends a paragraph (ms). See the class KDoc for the
     * derivation; internal for the pinning test.
     */
    internal const val PARAGRAPH_GAP_MS = 1_500L


    /**
     * Applies paragraph breaks to [text] using the sentence [cues]' gaps.
     * Falls back to [text] unchanged when the cues do not align to it.
     */
    fun apply(cues: List<TimedSegment>, text: String): String {
        if (cues.size < 2 || text.isBlank()) return text
        // Review F1: when NO gap qualifies there is nothing to add, and a
        // rebuild would still rewrite every inter-cue separator (collapsing
        // any non-space whitespace the text carried) for zero value: the
        // text returns untouched unless at least one paragraph break will
        // land.
        val hasParagraphGap = cues.zipWithNext().any { (a, b) -> b.startMs - a.endMs >= PARAGRAPH_GAP_MS }
        if (!hasParagraphGap) return text
        // The alignment gate: the cues must carry exactly the transcript's
        // words. Comparing whitespace-normalized forms is enough because the
        // rebuild only ever changes whitespace BETWEEN cues.
        if (normalize(cues.joinToString(" ") { it.text }) != normalize(text)) return text
        val out = StringBuilder(text.length + cues.size * 2)
        cues.forEachIndexed { i, cue ->
            if (i > 0) {
                val gap = cue.startMs - cues[i - 1].endMs
                out.append(if (gap >= PARAGRAPH_GAP_MS) "\n\n" else " ")
            }
            out.append(cue.text)
        }
        return out.toString()
    }

    private fun normalize(s: String): String = s.replace(RepetitionCollapse.WHITESPACE, " ").trim()
}
