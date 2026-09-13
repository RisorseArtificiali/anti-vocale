package com.antivocale.app.ui.components

import androidx.compose.animation.core.RepeatMode
import com.antivocale.app.ui.MAX_RENDERED_TRANSCRIPT_CHARS
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antivocale.app.R
import com.antivocale.app.ui.viewmodel.LogsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Compact composable for the Picture-in-Picture window.
 * Shows real-time transcription text with a minimal header.
 */
@Composable
fun PipTranscriptionView(
    viewModel: LogsViewModel = hiltViewModel()
) {
    val activeTranscription by viewModel.activeTranscription.collectAsState()
    val scrollState = rememberScrollState()
    var previousTextLength by remember { mutableIntStateOf(0) }

    // Auto-scroll when new text arrives
    LaunchedEffect(Unit) {
        snapshotFlow { activeTranscription?.result?.length }
            .distinctUntilChanged()
            .collect { length ->
                if (length != null && length > previousTextLength) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
                previousTextLength = length ?: 0
            }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Header: transcribing indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.pip_transcribing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                // Pulsing dot
                val infiniteTransition = rememberInfiniteTransition()
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            CircleShape
                        )
                        // TASK-386: decorative pulse; the adjacent label carries the
                        // state, so the dot must never take TalkBack focus in the
                        // tiny PiP window.
                        .clearAndSetSemantics { }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Transcription text with auto-scroll
            val displayText = activeTranscription?.result
            if (displayText.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.pip_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val pipRenderCap = remember(displayText, fontScale) {
                    (MAX_RENDERED_TRANSCRIPT_CHARS / fontScale.coerceAtLeast(1f)).toInt()
                        .coerceAtLeast(1_000)
                }
                Text(
                    // TASK-506 /simplify F-B: the SHARED render cap, as
                    // takeLast so a growing stream keeps its LIVE tail
                    // (code review: take() froze the oldest prefix and the
                    // pane stopped following the stream), scaled by
                    // fontScale so huge accessibility text cannot re-breach
                    // the 262142px Constraints ceiling in the narrow PiP.
                    text = displayText.takeLast(pipRenderCap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    lineHeight = 16.sp
                )
            }
        }
    }
}
