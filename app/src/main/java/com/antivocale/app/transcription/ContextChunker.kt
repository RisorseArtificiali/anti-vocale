package com.antivocale.app.transcription

/**
 * TASK-520: splits text that exceeds the LLM context guard into pieces that
 * each fit it, at the least destructive boundary available (sentence, then
 * newline, then a hard cut on the last resort). Pure Kotlin, shared by the
 * summary pass's map-reduce; the punctuation pass can adopt it for the same
 * 12k guard when it grows a chunked mode.
 *
 * The chunk budget equals [PunctuationPolicy.MAX_TRANSCRIPT_CHARS]: that
 * constant is already the "safe for one generation" transcript length (the
 * guard admits texts up to it), so every chunk it produces is admissible by
 * the same rule with no second magic number.
 */
object ContextChunker {

    /**
     * Splits [text] into chunks of at most [maxChars] (default: the context
     * guard). Boundary preference per chunk: the LAST sentence-ender inside
     * the budget, else the last newline, else the last space, else a hard
     * cut (a single word longer than the budget). Blank results are never
     * produced; identity (one chunk) when the text already fits.
     */
    fun split(text: String, maxChars: Int = PunctuationPolicy.MAX_TRANSCRIPT_CHARS): List<String> {
        if (maxChars <= 0) return listOf(text)
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= maxChars) {
                chunks += rest
                break
            }
            val window = rest.substring(0, maxChars)
            val cut = lastSentenceBoundary(window)
                ?: window.lastIndexOf('\n').takeIf { it > 0 }
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: maxChars
            // A boundary or space consumes itself (+1); the hard-cut case has
            // nothing to consume (cut == maxChars), so it must not skip one.
            val consume = if (cut == maxChars) cut else cut + 1
            chunks += rest.substring(0, consume).trimEnd()
            rest = rest.substring(minOf(consume, rest.length)).trimStart()
        }
        return chunks.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    /**
     * The offset of the last sentence-ender (., !, ?, …, or the ellipsis
     * char) in [window] whose following position is in-window whitespace, so a
     * decimal like "3.5" never reads as a boundary. Offset of the PERIOD is
     * returned (the caller's +1 then consumes the following space).
     */
    private fun lastSentenceBoundary(window: String): Int? {
        for (i in window.indices.reversed()) {
            val c = window[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // The window is always a strict prefix of the text (it is
                // built only when more text follows), so a boundary at the
                // window's last position has an UNSEEN next character that
                // can be a digit: "3." + "14159" must not split. Only an
                // in-window whitespace after the ender confirms it.
                val after = window.getOrNull(i + 1)
                if (after?.isWhitespace() == true) return i
            }
        }
        return null
    }
}
