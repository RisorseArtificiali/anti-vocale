package com.antivocale.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.benchmark.BenchmarkResult
import com.antivocale.app.benchmark.BenchmarkState
import com.antivocale.app.benchmark.SpeedRating
import java.util.concurrent.TimeUnit

/**
 * Dialog showing benchmark progress and results for a transcription model.
 */
@Composable
fun BenchmarkDialog(
    modelName: String,
    state: BenchmarkState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = {
            if (state !is BenchmarkState.Running) onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.benchmark_title, modelName),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "benchmark_state"
            ) { currentState ->
                when (currentState) {
                    is BenchmarkState.Idle -> {
                        Text(stringResource(R.string.benchmark_preparing))
                    }
                    is BenchmarkState.Running -> {
                        BenchmarkRunningContent(progress = currentState.progress)
                    }
                    is BenchmarkState.Complete -> {
                        BenchmarkResultContent(result = currentState.result)
                    }
                    is BenchmarkState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is BenchmarkState.Running -> {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.benchmark_cancel))
                    }
                }
                is BenchmarkState.Complete -> {
                    Row {
                        TextButton(onClick = onRerun) {
                            Text(stringResource(R.string.benchmark_rerun))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
                is BenchmarkState.Error -> {
                    Row {
                        TextButton(onClick = onRerun) {
                            Text(stringResource(R.string.benchmark_retry))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
                is BenchmarkState.Idle -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun BenchmarkRunningContent(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.benchmark_running),
            style = MaterialTheme.typography.bodyLarge
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.benchmark_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BenchmarkResultContent(result: BenchmarkResult) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Rating badge
        Surface(
            color = ratingColor(result.rating),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = ratingLabel(result.rating),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Inference speed
        MetricRow(
            icon = { Icon(Icons.Default.Speed, contentDescription = null) },
            label = stringResource(R.string.benchmark_speed_label),
            value = stringResource(R.string.benchmark_speed_value, result.secondsPerMinute)
        )

        // Inference time
        MetricRow(
            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
            label = stringResource(R.string.benchmark_time_label),
            value = stringResource(R.string.benchmark_time_value, result.inferenceTimeMs)
        )

        // Memory usage
        MetricRow(
            icon = { Icon(Icons.Default.Memory, contentDescription = null) },
            label = stringResource(R.string.benchmark_memory_label),
            value = stringResource(R.string.benchmark_memory_value, result.peakMemoryMb)
        )

        // Audio duration
        MetricRow(
            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
            label = stringResource(R.string.benchmark_duration_label),
            value = stringResource(R.string.benchmark_duration_value, result.audioDurationSeconds)
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.benchmark_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetricRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun ratingColor(rating: SpeedRating): Color = when (rating) {
    SpeedRating.FAST -> Color(0xFF4CAF50)
    SpeedRating.GOOD -> Color(0xFF8BC34A)
    SpeedRating.MODERATE -> Color(0xFFFF9800)
    SpeedRating.SLOW -> Color(0xFFF44336)
}

@Composable
private fun ratingLabel(rating: SpeedRating): String = when (rating) {
    SpeedRating.FAST -> stringResource(R.string.benchmark_rating_fast)
    SpeedRating.GOOD -> stringResource(R.string.benchmark_rating_good)
    SpeedRating.MODERATE -> stringResource(R.string.benchmark_rating_moderate)
    SpeedRating.SLOW -> stringResource(R.string.benchmark_rating_slow)
}
