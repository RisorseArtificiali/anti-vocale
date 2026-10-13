package com.antivocale.app.transcription

/**
 * TASK-816 (TASK-792 option 2): the decode-boundary repetition-loop
 * truncation for offline-whisper decodes, ported from sherpa-onnx's own
 * Qwen3-ASR guard (IsDegenerateRepetition / TrimDegenerateTail in
 * sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc:315-389, pinned
 * srclib commit 11afbd0; read at the pin, not from memory). Upstream ships
 * that guard for Qwen3 token ids only; greedy whisper on degenerate audio
 * loops the same way (TASK-792: 'Ehm...' x46 Italian, 'אי' x40 Hebrew) and
 * upstream has nothing for it. Pure Kotlin, no Android imports, so the pass
 * is JVM-testable like its sibling [RepetitionCollapse].
 *
 * The port adapts two granularities. First, whisper decodes cross the JNI
 * as TEXT, so the guard measures WORDS (through
 * [RepetitionCollapse.tokenKey], the package's ONE word-identity
 * normalizer) where qwen3 measures generated token ids. Second, qwen3
 * checks after EVERY generated token and stops generation at the first
 * degenerate window, so its loop never outruns the window and its trim can
 * floor at the window start; this guard sees the FINAL text of a decode
 * that already ran to completion, where the loop is typically LONGER than
 * the window, so the walk-back is unbounded: it drops trailing loop words
 * back to the run's true start (the first word outside the loop set). The
 * window still decides FIRING only; a clean tail never fires, so the
 * unbounded walk only ever touches already-degenerate text.
 *
 * BOUNDARY with [RepetitionCollapse] (both KDocs own half the contract):
 * THIS pass truncates at the decode boundary, applied by the backends
 * through [TranscriptionBackend.applyDegenerateRepetition] when they
 * declare [TranscriptionBackend.trimsDegenerateRepetition], so every
 * downstream consumer sees clean text (the four assembly paths, the live
 * interim partials, firstPassTranscript storage, cues). It fires only
 * on TIGHT tail loops (at most [MAX_DISTINCT_UNITS] distinct words in the
 * trailing window); a wider unit ('turn it down please' x20: four distinct
 * words) never fires here and stays the collapse's char-pass job at the
 * delivered funnel, as do mid-text loops, no-space-script loops, and
 * non-whisper backends. After this pass, a result's rawTranscript holds
 * the TRIMMED decode: the loop wall stays recoverable from the caller's
 * fire log line, not from the stored raw.
 */
object DegenerateRepetition {

    /**
     * The trailing window that decides FIRING, in words. qwen3 inspects 64
     * token ids; words are coarser units, so the equivalent speech span is
     * roughly a third of that count. A loop must run this long before the
     * guard fires: a stutter or a short emphatic run never triggers it.
     */
    const val WINDOW_WORDS = 24

    /**
     * The most distinct word identities the trailing window may hold
     * before the tail stops being a loop (qwen3's constant, unchanged).
     */
    const val MAX_DISTINCT_UNITS = 3

    /**
     * The trimmed outcome. [keptWords] is the surviving word count (where
     * the outermost collapse started); [droppedWords] is 0 when no loop
     * fired.
     */
    data class Trimmed(val text: String, val keptWords: Int, val droppedWords: Int) {
        val fired: Boolean get() = droppedWords > 0
    }

    /**
     * Trims every degenerate repetition tail from [text], repeating until
     * stable so tandem loops (loop A followed by loop B) both drop in one
     * call. Fires only when the text holds at least [WINDOW_WORDS] words
     * AND its trailing [WINDOW_WORDS]-word window holds at most
     * [MAX_DISTINCT_UNITS] distinct word identities: then trailing words
     * whose identity sits in that window's loop set are dropped, walking
     * backwards to the run's true start. Clean text returns itself
     * verbatim; the no-fire early returns never rebuild the string, so
     * callers comparing by reference or equality are never surprised.
     *
     * A trim that would empty the text (the whole decode was the loop,
     * no lead-in words) returns the ORIGINAL as a no-fire: an empty decode
     * is not silence (it would enter the blank-chunk machinery and the
     * silent-decode demotion, misattributing a garbage-audio decode), and
     * the delivered-funnel collapse already handles the pure-loop shape.
     */
    fun trim(text: String): Trimmed {
        val words = text.split(RepetitionCollapse.WHITESPACE).filter { it.isNotEmpty() }
        var current = words
        var droppedTotal = 0
        // Bounded: every fired iteration drops at least WINDOW_WORDS words.
        while (current.size >= WINDOW_WORDS) {
            val loopSet = current.takeLast(WINDOW_WORDS)
                .map { RepetitionCollapse.tokenKey(it) }.toSet()
            if (loopSet.size > MAX_DISTINCT_UNITS) break
            var keep = current.size
            while (keep > 0 && RepetitionCollapse.tokenKey(current[keep - 1]) in loopSet) {
                keep--
            }
            if (keep == current.size) break
            droppedTotal += current.size - keep
            current = current.subList(0, keep)
        }
        if (droppedTotal == 0 || current.isEmpty()) return Trimmed(text, words.size, 0)
        return Trimmed(
            text = sliceThroughWord(text, current.size),
            keptWords = current.size,
            droppedWords = droppedTotal,
        )
    }

    /**
     * The kept prefix as a SLICE of the original string (never a rejoin):
     * surviving lead-in text keeps its line breaks and spacing, the hazard
     * RepetitionCollapse's review F5 documented for the applied path. The
     * cut lands at the END of word [keptWordCount], word boundaries under
     * the same \s set the split counts words with.
     */
    private fun sliceThroughWord(text: String, keptWordCount: Int): String {
        var seen = 0
        var i = 0
        while (i < text.length) {
            if (text[i].isRegexWhitespace()) {
                i++
                continue
            }
            var j = i
            while (j < text.length && !text[j].isRegexWhitespace()) j++
            seen++
            if (seen == keptWordCount) return text.substring(0, j)
            i = j
        }
        return text
    }

    /** The exact char set of the \s class the word split uses (not the
     * broader Char.isWhitespace, which would disagree on Unicode spaces). */
    private fun Char.isRegexWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' ||
            this == '\u000B' || this == '\u000C' || this == '\r'
}
