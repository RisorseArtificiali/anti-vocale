package com.antivocale.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
    val scroll = if (capped) {
        Modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Text(
        text = annotated,
        style = style,
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .background(container.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
            .padding(8.dp)
            .then(scroll),
    )
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
    }
}
