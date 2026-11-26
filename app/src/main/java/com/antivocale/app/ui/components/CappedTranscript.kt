package com.antivocale.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.ui.MAX_RENDER_BLOCK_CHARS
import com.antivocale.app.ui.MAX_RENDER_BLOCK_LINES
import com.antivocale.app.ui.MAX_RENDERED_TRANSCRIPT_CHARS

/**
 * TASK-506: the shared transcript-render cap machinery. The constant's
 * doc (TranscriptRenderCap.kt) carries the why; this file carries the
 * behavior so no surface re-implements it (code review: the PiP inline
 * copy had already diverged, frozen tail and all).
 */

/**
 * Highlights all occurrences of [query] in [text], case-insensitive.
 *
 * Matching runs on the ORIGINAL string via indexOf(ignoreCase = true):
 * the previous lowerText/lowerQuery approach found indexes in the
 * lowercased copy and sliced the original with them, so any
 * length-changing case mapping (Turkish 'İ' lowercases to two chars)
 * shifted indexes past the end and crashed with
 * StringIndexOutOfBoundsException (code review, proven by compile).
 *
 * Pure string work (no composable reads) so callers can remember the
 * result.
 */
internal fun highlightText(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = text.indexOf(query, currentIndex, ignoreCase = true)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.Bold,
                    background = highlightColor.copy(alpha = 0.15f),
                )
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            currentIndex = matchIndex + query.length
        }
    }
}

/**
 * TASK-1034 (GH #190): one renderable block of a long transcript, split
 * for the per-node Constraints safety (TASK-506). [base] is the block's
 * offset in the ORIGINAL string, so global consumers (word taps, search
 * offsets) address the full text unchanged; concatenating every block's
 * [text] reproduces the original exactly.
 */
internal data class RenderBlock(val base: Int, val text: String)

/**
 * TASK-1034: splits [text] into render blocks of at most [maxBlockChars]
 * each. Paragraph boundaries ('\n') are preferred (words never split, and
 * a search match cannot straddle a NEWLINE seam, which the single-line
 * search field cannot query across); the whitespace fallback seam can
 * still split a multi-word occurrence that crosses it, and the fallback
 * hard-cuts mid-word whenever the window's last space sits in its first
 * half (both rare, both visible only as a missed highlight). A single
 * monster paragraph with no newline takes the whitespace fallback; a
 * newline-dense window is line-bounded at [maxBlockLines] because the
 * Constraints breach is line-count driven, not char-count driven.
 * Pure string work, unit-tested; the identity (concat == original) and
 * the per-block ceiling are the two pinned properties.
 */
internal fun splitForRender(
    text: String,
    maxBlockChars: Int,
    maxBlockLines: Int,
): List<RenderBlock> {
    if (text.length <= maxBlockChars) return listOf(RenderBlock(0, text))
    val blocks = mutableListOf<RenderBlock>()
    var base = 0
    while (base < text.length) {
        // The scans are WINDOW-LOCAL on purpose: a whole-string lastIndexOf
        // would rescan the entire prefix once per block and turn the 1.28M
        // monster into ~200M comparisons at first composition (simplify
        // round). The window copy costs at most maxBlockChars.
        val window = text.substring(base, minOf(base + maxBlockChars, text.length))
        // The LINE bound outranks the char bound: the Constraints breach
        // is line-count driven, and a newline-dense window of maxBlockChars
        // is thousands of rendered lines (code-review round). Cutting at
        // the maxBlockLines-th newline keeps every block line-bounded.
        val nthNewline = nthIndexOf(window, '\n', maxBlockLines)
        val cut = when {
            nthNewline != null && nthNewline > 0 -> nthNewline
            else -> window.lastIndexOf('\n').takeIf { it > 0 }
                ?: window.lastIndexOf(' ').takeIf { it > window.length / 2 }
                ?: window.length
        }
        blocks.add(RenderBlock(base, window.substring(0, cut)))
        base += cut
    }
    return blocks
}

/** Index of the [n]-th occurrence of [c] in [s], or null when there are
 *  fewer than n occurrences. */
private fun nthIndexOf(s: String, c: Char, n: Int): Int? {
    var count = 0
    var i = s.indexOf(c)
    while (i != -1) {
        count++
        if (count == n) return i
        i = s.indexOf(c, i + 1)
    }
    return null
}

/**
 * TASK-506/1034: bounded, scrollable transcript render. The whole
 * derivation is remembered, so recompositions of an unchanged card
 * (every search keystroke, every scroll) allocate nothing. The inner
 * scroll exists ONLY on long cards: ordinary transcripts keep full
 * height and the History list keeps exclusive drag ownership (code
 * review: an unconditional inner scrollable changed scrolling for every
 * long transcript, when only the >30K case needed bounding).
 *
 * TASK-1034 (GH #190): the panel renders EVERY character as a column of
 * per-paragraph blocks (splitForRender), so no single measured node can
 * approach Compose's Constraints limit however long the recording; a
 * 2-hour transcript is fully readable in the panel, search highlight
 * applies per block (a match cannot straddle a newline boundary), and
 * the word-tap read-along keeps global offsets (each block adds its
 * base). The PiP live view keeps its own scaled tail cap: a compact
 * live widget, not a reading surface.
 *
 * TASK-928: [wordTaps] + [onWordTap] make each word a seek target (the
 * History read-along). Null taps (or a null callback) render exactly the
 * plain surface; the tap is a plain pointerInput, no per-word annotation,
 * so the hot list surface gains nothing until a timed row expands.
 */
@Composable
internal fun CappedTranscriptText(
    text: String,
    searchQuery: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    textColor: Color = Color.Unspecified,
    onAutoSaveHintClick: (() -> Unit)? = null,
    wordTaps: TranscriptWordTaps? = null,
    onWordTap: ((Long) -> Unit)? = null,
) {
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val panelled = text.length > MAX_RENDERED_TRANSCRIPT_CHARS
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (panelled) {
        // The SPLIT keys on the text alone (code-review round): it does
        // not depend on the query, so a search keystroke must not re-copy
        // the whole transcript into windows; only the per-block highlight
        // below re-derives, exactly the blocks whose input changed.
        val split = remember(text) {
            splitForRender(text, MAX_RENDER_BLOCK_CHARS, MAX_RENDER_BLOCK_LINES)
        }
        // GH #94 + TASK-1034: position indicator + reading progress on the
        // scrollable panel, now spanning the FULL text. The indicator
        // modifier sits on the wrapping Box (outer to the scroll); the
        // viewport height is captured only for the progress line, which
        // sits outside the panel and cannot self-measure it.
        val state = rememberScrollState()
        var viewportPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 336.dp)
                .background(container.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                .transcriptPositionIndicator(state, indicatorColor)
                .onSizeChanged { viewportPx = it.height }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(state),
            ) {
                split.forEach { block ->
                    val annotated = remember(block, searchQuery, highlightColor) {
                        highlightText(block.text, searchQuery, highlightColor)
                    }
                    TranscriptTextNode(
                        annotated = annotated,
                        base = block.base,
                        style = style,
                        color = textColor,
                        wordTaps = wordTaps,
                        onWordTap = onWordTap,
                    )
                }
            }
        }
        ReadingProgressLine(
            state = state,
            color = indicatorColor,
            viewportPx = { viewportPx },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.transcript_render_scrolls),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // GH #94: surface the auto-save feature at the moment of need: the
        // user is looking at a long transcript in a bounded panel, exactly
        // when the full-text file export is most useful. TASK-617: rendered
        // ONLY when the caller provides the destination (a link-styled row
        // with no click reads as broken), and at most once per card: the
        // primary result/interim surface passes the callback, the labeled
        // blocks do not. The three modifiers move together: heightIn(min
        // 48dp) is the full-row touch target, wrapContentSize(CenterStart)
        // keeps the label vertically centered in that band (dropping it
        // top-aligns the words, dropping heightIn shrinks the target).
        if (onAutoSaveHintClick != null) {
            Text(
                text = stringResource(R.string.transcript_capped_autosave_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onAutoSaveHintClick() }
                    .wrapContentSize(Alignment.CenterStart),
            )
        }
    } else {
        val annotated = remember(text, searchQuery, highlightColor) {
            highlightText(text, searchQuery, highlightColor)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(container.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                .padding(8.dp),
        ) {
            TranscriptTextNode(
                annotated = annotated,
                base = 0,
                style = style,
                color = textColor,
                wordTaps = wordTaps,
                onWordTap = onWordTap,
            )
        }
    }
}

@Composable
private fun TranscriptTextNode(
    annotated: AnnotatedString,
    base: Int,
    style: TextStyle,
    color: Color,
    wordTaps: TranscriptWordTaps?,
    onWordTap: ((Long) -> Unit)?,
) {
    var layoutResult by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .wordTapPointer(wordTaps, onWordTap, base) { layoutResult },
    )
}

/**
 * TASK-928: the word-tap surface, attached ONLY when both the tap targets
 * and the destination exist. Taps translate through the laid-out text to a
 * character offset, add the block's [base] to make it global, and from
 * there to the word's start; taps between words and past the text are
 * no-ops. Attached per block INSIDE the scrollable column so the scroll
 * keeps drag priority and a swipe never fires a word.
 */
private fun Modifier.wordTapPointer(
    taps: TranscriptWordTaps?,
    onWordTap: ((Long) -> Unit)?,
    base: Int,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?,
): Modifier {
    if (taps == null || onWordTap == null) return this
    return pointerInput(taps, onWordTap, base) {
        detectTapGestures { position ->
            val textLayout = layout() ?: return@detectTapGestures
            taps.startMsAt(base + textLayout.getOffsetForPosition(position))?.let(onWordTap)
        }
    }
}

/**
 * GH #94: pure thumb geometry for the position indicator, unit-tested.
 * Returns (topOffsetPx, heightPx) on a track of [trackPx] height, or null
 * when there is nothing to scroll (maxValue <= 0: the indicator is hidden).
 * The thumb covers the viewport's fraction of the content (viewportPx /
 * (viewportPx + maxValue)) with a floor of [minThumbPx] so it stays visible,
 * and never exceeds the track.
 */
internal fun thumbGeometry(
    value: Int,
    maxValue: Int,
    viewportPx: Int,
    trackPx: Int,
    minThumbPx: Int,
): Offset? {
    if (maxValue <= 0 || trackPx <= 0 || viewportPx <= 0) return Offset.Unspecified
    val content = viewportPx + maxValue
    val minThumb = minOf(minThumbPx.toFloat(), trackPx.toFloat())
    val h = (trackPx * viewportPx.toFloat() / content).coerceIn(minThumb, trackPx.toFloat())
    // The thumb's travel is what remains of the track after its own height.
    val travel = trackPx - h
    val top = travel * (value.toFloat() / maxValue)
    // x = thumb height, y = thumb top: a packed value class, no Pair per
    // draw frame (TASK-898).
    return Offset(h, top)
}

/**
 * GH #94: pure reading-progress fraction for the progress line, unit-tested.
 * How much of the content has been SEEN (not where the viewport sits): the
 * visible span is (value + viewport), so the end state reads as a full line
 * even though the scroll position tops out earlier. Clamped to [0, 1];
 * [Float.NaN] when there is nothing to scroll (drawn as an empty track).
 */
internal fun progressFraction(
    value: Int,
    maxValue: Int,
    viewportPx: Int,
): Float {
    if (maxValue <= 0 || viewportPx <= 0) return Float.NaN
    return ((value + viewportPx).toFloat() / (maxValue + viewportPx)).coerceIn(0f, 1f)
}

/** Material norm for an indicative (non-draggable) scrollbar on mobile. */
private val INDICATOR_WIDTH = 4.dp
private val INDICATOR_MIN_THUMB = 24.dp

/**
 * GH #94: draws a position indicator (faint track + rounded thumb) on the
 * right edge of the modified node's own bounds. Attach it OUTER to the
 * verticalScroll modifier (on the wrapping panel, or before .verticalScroll
 * in the chain): a draw modifier inner to the scroll measures the CONTENT
 * height, not the viewport, and its drawing scrolls away with the text.
 * The state reads live in the draw phase only, so scrolling invalidates
 * the draw pass, never composition. Pure overlay, not a drag handle: the
 * panel remains the scrollable.
 */
internal fun Modifier.transcriptPositionIndicator(
    state: ScrollState,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    val geo = thumbGeometry(
        value = state.value,
        maxValue = state.maxValue,
        viewportPx = size.height.toInt(),
        trackPx = size.height.toInt(),
        minThumbPx = INDICATOR_MIN_THUMB.toPx().toInt(),
    ) ?: return@drawWithContent
    val w = INDICATOR_WIDTH.toPx()
    val x = size.width - w
    drawRoundRect(
        color = color.copy(alpha = 0.15f),
        cornerRadius = CornerRadius(w / 2),
        topLeft = Offset(x, 0f),
        size = Size(w, size.height),
    )
    drawRoundRect(
        color = color.copy(alpha = 0.70f),
        cornerRadius = CornerRadius(w / 2),
        topLeft = Offset(x, geo.y),
        size = Size(w, geo.x),
    )
}

/**
 * GH #94: reading-progress line under a capped transcript panel: the
 * codebase's standard determinate bar (ModelVariantCard, BenchmarkDialog)
 * fed with the SEEN fraction. The visible span is (value + viewport), so
 * reaching the end of the content fills the whole width, the unmistakable
 * end state the issue asks for. [viewportPx] comes from the panel above
 * (onSizeChanged): the line is 2dp tall and cannot self-measure it.
 */
@Composable
internal fun ReadingProgressLine(
    state: ScrollState,
    color: Color,
    // TASK-898: a lambda, not an Int: the scroll position AND the viewport
    // size are read inside the progress lambda (Draw phase), so every
    // scroll pixel invalidates draw only instead of recomposing this
    // line, and the onSizeChanged write stops being a Composition-read
    // backwards write (the first frame used to compute on a NaN that
    // callers papered over).
    viewportPx: () -> Int,
    modifier: Modifier = Modifier,
) {
    // TASK-384 pattern (ModelVariantCard, BenchmarkDialog): TalkBack reads
    // the same percentage the bar shows, not a raw range. The percentage
    // is derived in 5-point buckets and read HERE, in composition (the
    // in-repo TASK-384 form: a value captured by the semantics block is
    // only as fresh as the recomposition that produced it, so the read
    // must sit in the body); bucketing keeps the recompositions to ~20
    // per full scroll instead of one per pixel, and the bar itself stays
    // draw-phase deferred above.
    val talkbackPercent = remember(state) {
        androidx.compose.runtime.derivedStateOf {
            val fraction = progressFraction(state.value, state.maxValue, viewportPx())
            if (fraction.isNaN()) -1 else ((fraction * 100).toInt() / 5) * 5
        }
    }.value
    LinearProgressIndicator(
        progress = {
            val fraction = progressFraction(state.value, state.maxValue, viewportPx())
            if (fraction.isNaN()) 0f else fraction
        },
        modifier = modifier
            .height(2.dp)
            .semantics {
                if (talkbackPercent >= 0) {
                    stateDescription = "$talkbackPercent%"
                }
            },
        color = color.copy(alpha = 0.50f),
        trackColor = color.copy(alpha = 0.12f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
