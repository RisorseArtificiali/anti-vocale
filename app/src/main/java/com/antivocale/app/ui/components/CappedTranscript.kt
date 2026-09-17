package com.antivocale.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
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
 * TASK-506: bounded, scrollable transcript render. The whole derivation
 * (cap + highlight) is remembered, so recompositions of an unchanged
 * card (every search keystroke, every scroll) allocate nothing. The
 * inner scroll exists ONLY on capped cards: ordinary long transcripts
 * keep full height and the History list keeps exclusive drag ownership
 * (code review: an unconditional inner scrollable changed scrolling for
 * every long transcript, when only the >30K case needed bounding).
 * Search highlights apply to the capped prefix only: a match beyond the
 * cap is reachable through Copy, a deliberate trade-off.
 */
@Composable
internal fun CappedTranscriptText(
    text: String,
    searchQuery: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    textColor: Color = Color.Unspecified,
) {
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val capped = text.length > MAX_RENDERED_TRANSCRIPT_CHARS
    val annotated = remember(text, searchQuery, highlightColor) {
        val rendered = if (capped) text.take(MAX_RENDERED_TRANSCRIPT_CHARS) else text
        highlightText(rendered, searchQuery, highlightColor)
    }
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (capped) {
        // GH #94: position indicator + reading progress on the capped panel.
        // The indicator modifier sits on the wrapping Box (outer to the
        // scroll); the viewport height is captured only for the progress
        // line, which sits outside the panel and cannot self-measure it.
        // The scroll viewport keeps its historical 320dp (336dp total with
        // the padding the viewport now lives inside); the cap is untouched.
        val state = rememberScrollState()
        var viewportPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 320dp of SCROLL VIEWPORT + the 16dp of padding the viewport
                // now lives inside (the original bound was on the scroll node,
                // paddings outside).
                .heightIn(max = 336.dp)
                .background(container.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                .transcriptPositionIndicator(state, indicatorColor)
                .onSizeChanged { viewportPx = it.height }
        ) {
            Text(
                text = annotated,
                style = style,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(state),
            )
        }
        ReadingProgressLine(
            state = state,
            color = indicatorColor,
            viewportPx = viewportPx,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    } else {
        Text(
            text = annotated,
            style = style,
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .background(container.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                .padding(8.dp),
        )
    }
    if (capped) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.transcript_render_capped,
                MAX_RENDERED_TRANSCRIPT_CHARS,
                text.length - MAX_RENDERED_TRANSCRIPT_CHARS,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // GH #94: surface the auto-save feature at the moment of need: the
        // user is looking at a truncated transcript, which is exactly when
        // the full-text file export is most useful and least known.
        Text(
            text = stringResource(R.string.transcript_capped_autosave_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
): Pair<Float, Float>? {
    if (maxValue <= 0 || trackPx <= 0 || viewportPx <= 0) return null
    val content = viewportPx + maxValue
    val minThumb = minOf(minThumbPx.toFloat(), trackPx.toFloat())
    val h = (trackPx * viewportPx.toFloat() / content).coerceIn(minThumb, trackPx.toFloat())
    // The thumb's travel is what remains of the track after its own height.
    val travel = trackPx - h
    val top = travel * (value.toFloat() / maxValue)
    return top to h
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
        topLeft = Offset(x, geo.first),
        size = Size(w, geo.second),
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
    viewportPx: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = progressFraction(state.value, state.maxValue, viewportPx)
    LinearProgressIndicator(
        progress = { if (fraction.isNaN()) 0f else fraction },
        // TASK-384 pattern (ModelVariantCard, BenchmarkDialog): TalkBack reads
        // the same percentage the bar shows, not a raw range.
        modifier = modifier
            .height(2.dp)
            .semantics {
                if (!fraction.isNaN()) {
                    stateDescription = "${(fraction * 100).toInt()}%"
                }
            },
        color = color.copy(alpha = 0.50f),
        trackColor = color.copy(alpha = 0.12f),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
