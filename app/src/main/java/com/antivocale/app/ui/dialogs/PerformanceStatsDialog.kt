package com.antivocale.app.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.TranscriptionCalibrator.CalibrationProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceStatsDialog(
    profiles: List<CalibrationProfile>,
    isTranscribing: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    var showResetConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.performance_stats_title))
            }
        },
        text = {
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.performance_stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.performance_stats_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Find slowest speed for relative calculation
                    val slowestMsPerSec = profiles.maxOf { it.msPerSecondOfAudio }
                    val fastestProfile = profiles.first()

                    profiles.forEach { profile ->
                        val isFastest = profile == fastestProfile && profiles.size > 1

                        // Real-time factor: lower ms/s = faster. RTF < 1.0 means faster than real-time.
                        val rtf = profile.msPerSecondOfAudio / 1000f
                        val speedLabel = if (rtf <= 1f) {
                            String.format("%.1fx real-time", 1f / rtf)
                        } else {
                            String.format("%.2fx real-time", 1f / rtf)
                        }
                        val relativeSpeed = if (slowestMsPerSec > 0 && profiles.size > 1) {
                            slowestMsPerSec / profile.msPerSecondOfAudio
                        } else null

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFastest)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Row 1: Model name + Fastest badge
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    if (isFastest) {
                                        Surface(
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = stringResource(R.string.performance_stats_fastest_badge),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Row 2: Speed + relative
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = speedLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isFastest) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (relativeSpeed != null) {
                                        Text(
                                            text = String.format("(%.1fx)", relativeSpeed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Row 3: Metadata
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.performance_stats_samples_count,
                                            profile.sampleCount
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (profile.lastTimestamp > 0) {
                                        Text(
                                            text = formatLastUsed(profile.lastTimestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Total audio processed
                    val totalAudio = profiles.sumOf { it.totalAudioSeconds }
                    if (totalAudio > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        Text(
                            text = stringResource(
                                R.string.performance_stats_total_audio,
                                formatAudioDuration(totalAudio)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profiles.isNotEmpty()) {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text(
                            text = stringResource(R.string.performance_stats_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    )

    // Reset confirmation dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.performance_stats_clear)) },
            text = {
                if (isTranscribing) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.performance_stats_clear_confirm))
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.warn_reset_stats_during_transcription),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.performance_stats_clear_confirm))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        showResetConfirm = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.performance_stats_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun formatLastUsed(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val elapsed = System.currentTimeMillis() - timestamp
    val minutes = elapsed / 60_000
    val hours = elapsed / 3_600_000
    val days = elapsed / 86_400_000

    return when {
        minutes < 1 -> stringResource(R.string.performance_stats_just_now)
        minutes < 60 -> stringResource(R.string.performance_stats_minutes_ago, minutes)
        hours < 24 -> stringResource(R.string.performance_stats_hours_ago, hours)
        else -> stringResource(R.string.performance_stats_days_ago, days)
    }
}

private fun formatAudioDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format("%dh %dm", hours, minutes)
        minutes > 0 -> String.format("%dm %ds", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}
