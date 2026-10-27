package com.antivocale.app.transcription

import java.text.Normalizer

/**
 * TASK-911: History search that finds keywords the recognizer transcribed
 * WRONG. The literal search misses a word the model wrote phonetically
 * similar but different (the frequent case on low-quality languages), and
 * those errors are ACOUSTIC, not typing typos: vowels shift, gemination
 * doubles or halves, one segment substitutes, and the consonant frame
 * mostly survives. So the fuzzy arm scores each query TERM against each
 * WORD of a transcript with two combined signals, both language-agnostic
 * by construction (no SoundEx/Metaphone: they are English-centric):
 *
 *  1. CONSONANT-SKELETON similarity: both sides normalized (lowercase,
 *     diacritics stripped, doubled letters collapsed), vowels dropped, then
 *     bigram-Dice on the skeletons. Catches vowel errors and homophone-ish
 *     confusions, the transcription-error signature.
 *  2. RESTRICTED DAMERAU-LEVENSHTEIN (optimal string alignment) similarity
 *     on the normalized full forms: catches one-segment substitutions and
 *     adjacent transpositions, no re-editing through a transposed pair.
 *
 * Per-term score is the MAX of the two signals; a term matches a row when
 * some word in the row scores at or above [THRESHOLD]; a row matches when
 * EVERY query term does (the same all-terms semantics as the literal
 * search). CJK needs no special case: edit distance and bigrams apply
 * unchanged, the diacritics strip is a no-op, and the scorer simply
 * degrades gracefully instead of excluding the language.
 */
object TranscriptFuzzySearch {

    /**
     * The per-term acceptance bar, pinned by the test corpus: the weakest
     * accepted confusion (Bologna heard as Bolonia: 0.714, its only signal
     * edit distance over 7 characters) sits above it, and the strongest
     * rejected pair (era/ora: 0.667, one vowel apart in three letters)
     * below. Lower starts admitting single-vowel differences on every
     * common 3-letter word; higher drops the two-error family outright.
     * Internal for the pinning test.
     */
    internal const val THRESHOLD = 0.70

    private val VOWELS = charArrayOf('a', 'e', 'i', 'o', 'u')
    private val QUERY_SPLIT = Regex("\\s+")
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** A row matches when every query term finds a close-enough word. */
    internal fun matches(text: String, query: String): Boolean = scoreIfMatches(text, query) != null

    /**
     * The row's relevance (higher first) for ranking matched rows: the mean
     * over terms of the best word score. Only meaningful for rows that
     * already pass [matches]; unmatched rows read 0.
     */
    internal fun rowScore(text: String, query: String): Double = scoreIfMatches(text, query) ?: 0.0

    /**
     * The single scoring pass the search pipeline calls: the row's
     * relevance in [0, 1] when EVERY query term finds a word at or above
     * [THRESHOLD], null when the row does not match. Filtering and ranking
     * both consume this one value, and terms/words are normalized exactly
     * once per row per query.
     */
    fun scoreIfMatches(text: String, query: String): Double? {
        // Terms go through the SAME token rules as row words: whitespace
        // first, then non-letter/digit splits, so an Italian clitic query
        // ("l'acqua") becomes its inner words exactly like the row side
        // does and a mis-heard inner word stays findable.
        val rawTerms = query.trim().lowercase().split(QUERY_SPLIT)
            .filter { it.isNotEmpty() }
            .flatMap { it.split(WORD_SPLIT) }
            .filter { it.isNotEmpty() }
        if (rawTerms.isEmpty()) return null
        val rowWords = text.lowercase().split(WORD_SPLIT)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (rowWords.isEmpty()) return null
        var sum = 0.0
        for (rawTerm in rawTerms) {
            // Widening floor, checked FIRST because maxOf evaluates both
            // arms eagerly and a verbatim hit must not pay for the
            // whole-word scan. Without this floor SIMILAR would NARROW the
            // literal search: a partial-word query ("castel") or a CJK word
            // inside an unspaced sentence fails the whole-word length gate
            // even though the transcript contains it.
            if (text.contains(rawTerm, ignoreCase = true)) {
                sum += 1.0
                continue
            }
            // rawTerm is a letters-and-digits token (the split above), so
            // normalize() can never empty it: no empty-term arm exists.
            val term = normalize(rawTerm)
            val best = rowWords.maxOfOrNull { termScoreNormalized(it, term) } ?: 0.0
            if (best < THRESHOLD) return null
            sum += best
        }
        return sum / rawTerms.size
    }

    /**
     * Best combined score of one normalized word against one normalized
     * term, in [0, 1]. Callers normalize first ([scoreIfMatches]); the raw
     * public form below is for tests and one-off probes.
     */
    private fun termScoreNormalized(w: String, t: String): Double {
        if (w.isEmpty() || t.isEmpty()) return 0.0
        if (w == t) return 1.0
        // A term much longer than the word (or vice versa) cannot be an
        // acoustic confusion of it; the skeleton arm would happily pass
        // "casa" against "castellazzo" (skeleton c-s vs c-s-t-l-z shares
        // the bigram), so the length ratio gates both arms.
        val ratio = w.length.toDouble() / t.length
        if (ratio < 0.6 || ratio > 1.67) return 0.0
        val skeletonScore = diceBigrams(skeleton(w), skeleton(t))
        val editScore = 1.0 - damerau(w, t).toDouble() / maxOf(w.length, t.length)
        return maxOf(skeletonScore, editScore)
    }

    /** Best combined score of one raw word against one raw query term. */
    fun termScore(word: String, term: String): Double = termScoreNormalized(normalize(word), normalize(term))

    /**
     * Lowercase, diacritics folded to their base letter (é→e, č→c: the
     * acoustic signal rarely survives the diacritic), and doubled letters
     * collapsed (gemination is a classic ASR instability: castellazzo ↔
     * castelazo).
     */
    internal fun normalize(s: String): String {
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(folded.length)
        var last = '\u0000'
        for (c in folded) {
            // All three combining-mark classes fold: NON_SPACING (é→e),
            // COMBINING_SPACING (Devanagari matras) and ENCLOSING, so the
            // diacritic contract holds for Indic and Thai scripts too.
            val type = Character.getType(c)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            ) continue
            if (c != last) {
                sb.append(c)
                last = c
            }
        }
        return sb.toString()
    }

    /** Vowels dropped from the normalized form: the consonant frame. */
    private fun skeleton(normalized: String): String =
        normalized.filter { it !in VOWELS }

    /** Bigram Dice coefficient; empty skeletons score 0. */
    private fun diceBigrams(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val bi = mutableMapOf<String, Int>()
        for (i in 0 until a.length - 1) {
            val g = a.substring(i, i + 2)
            bi[g] = (bi[g] ?: 0) + 1
        }
        var shared = 0
        for (i in 0 until b.length - 1) {
            val g = b.substring(i, i + 2)
            val n = bi[g] ?: 0
            if (n > 0) {
                bi[g] = n - 1
                shared++
            }
        }
        return 2.0 * shared / (a.length - 1 + b.length - 1)
    }

    /**
     * Restricted Damerau-Levenshtein (optimal string alignment):
     * substitutions, insertions, deletions and adjacent transpositions,
     * where a transposed pair cannot be re-edited through.
     */
    private fun damerau(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j
        for (i in 1..la) {
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d[la][lb]
    }
}
