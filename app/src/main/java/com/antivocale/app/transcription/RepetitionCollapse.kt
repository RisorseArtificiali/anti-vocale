package com.antivocale.app.transcription

/**
 * TASK-672 (GH #72): the deterministic tier-1 hallucination-loop
 * collapse, ported from the maintainer's Omnivoice reference
 * (backend/services/refinement.py). Pure Kotlin, no Android imports, so
 * the whole pass is JVM-testable.
 *
 * A looping decoder emits one unit until its budget fills ("url url
 * url url..." or "thanks for watching thanks for watching..."), the
 * transcript class that once crashed the History list (TASK-506) and
 * that [RepetitionLoopDetector] only warns about. This pass rewrites
 * the loop down instead: two regex tiers, word runs first, then a
 * character-level pass for multi-word loops and no-space-script loops.
 * Both tiers need the unit to repeat [REPETITION_RUN_THRESHOLD] times
 * consecutively, so rhetorical repetition survives (anaphora, counted
 * lists, choruses at five repeats or fewer), and the repeating unit is
 * bounded at [MAX_REPETITION_UNIT_CHARS] so a long legitimate sentence
 * can never pair with itself as a "unit". No LLM anywhere in this
 * tier; the constants are the guard.
 */
object RepetitionCollapse {

    /**
     * A token/unit must repeat this many times CONSECUTIVELY before its
     * run drops. Five repeats survive: rhetorical repetition is a real
     * speech pattern, six-in-a-row of the same token is a decoder loop.
     */
    const val REPETITION_RUN_THRESHOLD = 6

    /** Upper bound of one repeating unit in the character-level pass. */
    const val MAX_REPETITION_UNIT_CHARS = 60

    /**
     * Review F2: the character pass scans in windows of this many chars
     * (with a unit-bound overlap), bounding the regex cost on megabyte
     * transcripts instead of one multi-second sweep.
     */
    private const val CHARACTER_WINDOW_CHARS = 64 * 1024

    /**
     * Collapses every hallucination loop in [text]: word runs of
     * [REPETITION_RUN_THRESHOLD]+ identical tokens drop whole, then the
     * character-level pass removes repeating units of up to
     * [MAX_REPETITION_UNIT_CHARS] chars seen [REPETITION_RUN_THRESHOLD]+
     * times in a row. Idempotent by construction; the tests verify a
     * second pass over the output changes nothing.
     */
    fun collapse(text: String): String = collapseCharacterLoops(collapseWordRuns(text))

    /**
     * Pass 1, word level: a run of [REPETITION_RUN_THRESHOLD] or more
     * consecutive tokens with the same [tokenKey] drops WHOLE
     * (Omnivoice's choice: the surrounding prose carries the thought);
     * shorter runs stay verbatim. Tokens rejoin with single spaces.
     */
    private fun collapseWordRuns(text: String): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val key = tokenKey(words[i])
            var j = i + 1
            // A null key never extends (an all-punctuation token
            // compares equal to nothing), so it stays a run of one.
            while (j < words.size && key != null && tokenKey(words[j]) == key) j++
            if (j - i < REPETITION_RUN_THRESHOLD) {
                kept.addAll(words.subList(i, j))
            } else {
                // Review F1: keep ONE copy. Dropping the whole run amputates
                // legitimate emphasis once it crosses the threshold: laughter
                // ("ah ah ah ah ah ah ah"), emphatic refusal ("no" x6),
                // dictated digit pairs. When repetition IS the content the
                // surrounding prose does not carry it; one copy keeps the
                // speech while still collapsing the loop's bulk.
                kept.add(words[i])
            }
            i = j
        }
        // Review F5: when no run dropped, return the ORIGINAL string; a
        // rebuild would flatten newlines and double spaces on clean text,
        // tripping the applied-path (spurious raw rows, lost line breaks).
        if (kept == words) return text
        return kept.joinToString(" ")
    }

    /**
     * Pass 2, character level: the shortest repeating unit of 2 to
     * [MAX_REPETITION_UNIT_CHARS] chars that recurs in place enough
     * times to total [REPETITION_RUN_THRESHOLD] copies, removed whole.
     * This is what catches multi-word loops with no consecutive
     * identical tokens ("thanks for watching" x6) and no-space-script
     * loops (CJK, Thai), which the word pass cannot see. The two-char
     * floor keeps emphasis runs ("hmmmmm", "woooow"): a single-letter
     * elongation can never form a unit. DOTALL so loops spanning line
     * breaks match too. Whitespace is normalized and stripped only when
     * the pass changed something.
     */
    private fun collapseCharacterLoops(text: String): String {
        // Review F2: one full-text regex scan costs ~1.4s per MB on clean
        // (non-looping) text on a desktop JVM, several times more on a
        // phone, paid at the serial funnel on EVERY long transcript.
        // Windowed scanning bounds that, and the unit-bound overlap keeps a
        // unit straddling a window edge matchable.
        val collapsed =
            if (text.length <= CHARACTER_WINDOW_CHARS) {
                collapseCharacterLoopsInWindow(text)
            } else {
                val out = StringBuilder(text.length)
                var start = 0
                while (start < text.length) {
                    val end = minOf(start + CHARACTER_WINDOW_CHARS, text.length)
                    val windowEnd = if (end < text.length) end + MAX_REPETITION_UNIT_CHARS else end
                    out.append(collapseCharacterLoopsInWindow(text.substring(start, minOf(windowEnd, text.length))))
                    start = windowEnd
                }
                out.toString()
            }
        if (collapsed == text) return text
        return WHITESPACE.replace(collapsed, " ").trim()
    }

    private fun collapseCharacterLoopsInWindow(window: String): String =
        CHARACTER_LOOP.replace(window, "")

    /**
     * The run identity of one token: non-word characters stripped,
     * lowercased, so "URL", "url," and "URL." compare equal. Null when
     * nothing survives the strip (an all-punctuation token): those never
     * join a run, so a stream of "," tokens never collapses.
     */
    private fun tokenKey(word: String): String? =
        word.filter { it.isLetterOrDigit() || it == '_' }
            .lowercase()
            .ifEmpty { null }

    private val WHITESPACE = Regex("\\s+")

    /** Non-greedy unit: the shortest repeating substring wins. */
    private val CHARACTER_LOOP =
        Regex("(?s)(.{2,$MAX_REPETITION_UNIT_CHARS}?)\\1{${REPETITION_RUN_THRESHOLD - 1},}")
}
