package com.antivocale.app.ui.components

/**
 * TASK-928: the pure tap geometry of a word-timed transcript. Maps a
 * character offset in the rendered text to the word's start timestamp.
 * Built from the text's own whitespace words; a timing list whose count
 * does not match them (an upstream alignment that failed) builds nothing,
 * so a row either taps exactly or not at all.
 */
class TranscriptWordTaps private constructor(
    private val startsMs: List<Long>,
    private val ranges: List<IntRange>,
) {

    /** The word's start when [charOffset] lands inside a word, else null. */
    fun startMsAt(charOffset: Int): Long? {
        for (i in ranges.indices) {
            if (charOffset in ranges[i]) return startsMs[i]
        }
        return null
    }

    companion object {

        /**
         * The tap targets of [text] against its per-word starts, null when
         * the counts disagree. Punctuation rides its word (it is inside the
         * non-whitespace run), matching the split the karaoke export and the
         * cue derivation use.
         */
        fun of(text: String, startsMs: List<Long>): TranscriptWordTaps? {
            val ranges = ArrayList<IntRange>(startsMs.size)
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                ranges.add(start until i)
            }
            if (ranges.size != startsMs.size) return null
            return TranscriptWordTaps(startsMs, ranges)
        }
    }
}
