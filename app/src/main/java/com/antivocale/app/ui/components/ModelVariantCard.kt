package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.download.DownloadState
import com.antivocale.app.transcription.ModelVariant
import com.antivocale.app.util.formatFileSize

// ==================== Download Button State ====================

/**
 * Sealed interface representing the possible button states for a model variant card.
 * Callers compute this from their UiState before passing it to [ModelVariantCard].
 */
sealed interface DownloadButtonState {
    /** Show Download button. */
    data object Idle : DownloadButtonState

    /** Show Cancel button. */
    data object Downloading : DownloadButtonState

    /** Show Use + Delete buttons. */
    data object Downloaded : DownloadButtonState

    /** No button — PartialDownloadSection shown above handles resume/clear. */
    data object PartiallyDownloaded : DownloadButtonState

    /** Whisper-specific: show Extract button. */
    data object NeedsExtraction : DownloadButtonState

    /** Whisper-specific: show Clear (orphaned files) button. */
    data object Orphaned : DownloadButtonState
}

// ==================== Card State ====================

/**
 * Bundles all state needed to render a single model variant card.
 *
 * Derived properties:
 * - [isDownloaded] — `buttonState is DownloadButtonState.Downloaded`
 * - [isDownloading] — `buttonState is DownloadButtonState.Downloading`
 * - [isPartialDownload] — `partialDownload != null`
 */
data class ModelVariantCardState<V : ModelVariant>(
    val variant: V,
    val isActive: Boolean,
    val downloadProgress: Float,
    val downloadState: DownloadState,
    val errorMessage: String?,
    val partialDownload: DownloadState.PartiallyDownloaded?,
    val buttonState: DownloadButtonState
) {
    val isDownloaded: Boolean get() = buttonState is DownloadButtonState.Downloaded
    val isDownloading: Boolean get() = buttonState is DownloadButtonState.Downloading
    val isPartialDownload: Boolean get() = partialDownload != null
}

// ==================== Generic Card ====================

/**
 * Generic model variant card shared by Qwen3-ASR and Whisper download sections.
 *
 * @param state All variant-specific state bundled together.
 * @param downloadButtonTextResId String resource for the download button (varies per backend).
 * @param extraBadges Optional composable injected after the variant title (e.g. Whisper "Recommended"/"Fastest" badges).
 * @param cancelTextExtractor Optional extractor for cancel button text (Whisper shows "Cancel extract" during extraction).
 * @param onDownloadClick Start download or extraction.
 * @param onCancelClick Cancel in-progress download.
 * @param onResumeClick Resume a partial download.
 * @param onClearPartialClick Clear a partial download.
 * @param onExtraActionClick Handle extra action (orphaned clear, etc.).
 * @param onUseClick Switch to this model.
 * @param onDeleteClick Delete this model.
 */
@Composable
fun ModelVariantCard(
    state: ModelVariantCardState<*>,
    downloadButtonTextResId: Int,
    modifier: Modifier = Modifier,
    extraBadges: @Composable (() -> Unit)? = null,
    cancelTextExtractor: (@Composable (DownloadState) -> String)? = null,
    onDownloadClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
    onResumeClick: () -> Unit = {},
    onClearPartialClick: () -> Unit = {},
    onExtraActionClick: () -> Unit = {},
    onUseClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.isDownloading -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            state.isDownloaded -> Icons.Default.CheckCircle
                            state.isDownloading -> Icons.Default.CloudDownload
                            else -> Icons.Default.Storage
                        },
                        contentDescription = null,
                        tint = when {
                            state.isDownloaded -> MaterialTheme.colorScheme.primary
                            state.isDownloading -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(state.variant.titleResId),
                                style = MaterialTheme.typography.titleSmall
                            )
                            extraBadges?.invoke()
                            if (state.isActive) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = stringResource(R.string.active_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(state.variant.descriptionResId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Download progress
            if (state.isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                DownloadProgressView(state.downloadState, state.downloadProgress)
            }

            // Error message
            state.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Partial download
            if (state.isPartialDownload) {
                state.partialDownload?.let { partial ->
                    Spacer(modifier = Modifier.height(8.dp))
                    PartialDownloadSection(
                        partial = partial,
                        onResumeClick = onResumeClick,
                        onClearClick = onClearPartialClick
                    )
                }
            }

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state.buttonState) {
                    is DownloadButtonState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(cancelTextExtractor?.invoke(state.downloadState) ?: stringResource(R.string.cancel_download))
                        }
                    }
                    is DownloadButtonState.Downloaded -> {
                        if (!state.isActive) {
                            Button(
                                onClick = onUseClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.use_model))
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        OutlinedButton(
                            onClick = onDeleteClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    is DownloadButtonState.PartiallyDownloaded -> {
                        // PartialDownloadSection above already shows Resume/Clear buttons
                    }
                    is DownloadButtonState.NeedsExtraction -> {
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.extract_model))
                        }
                    }
                    is DownloadButtonState.Orphaned -> {
                        OutlinedButton(
                            onClick = onExtraActionClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.download_clear_partial))
                        }
                    }
                    is DownloadButtonState.Idle -> {
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(downloadButtonTextResId))
                        }
                    }
                }
            }
        }
    }
}

// ==================== Shared Composables ====================

/**
 * Displays download progress, extraction progress, connecting, or retrying states.
 */
@Composable
internal fun DownloadProgressView(
    downloadState: DownloadState,
    downloadProgress: Float,
    showExtractingFileSize: Boolean = false
) {
    when (val state = downloadState) {
        is DownloadState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.download_status_downloading), style = MaterialTheme.typography.bodySmall)
                    Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = formatFileSize(state.bytesDownloaded) +
                        if (state.totalBytes > 0) " / ${formatFileSize(state.totalBytes)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadRateEta(state)
            }
        }
        is DownloadState.Extracting -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showExtractingFileSize && state.currentFileSize > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.download_status_extracting, state.fileName.takeIf { it.isNotEmpty() } ?: stringResource(R.string.download_status_file_progress, state.fileIndex, state.totalFiles)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${formatFileSize(state.bytesExtracted)} / ${formatFileSize(state.currentFileSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        if (state.fileName.isNotEmpty()) {
                            stringResource(R.string.download_status_extracting, state.fileName)
                        } else {
                            stringResource(R.string.download_status_extracting_files)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        is DownloadState.CheckingAccess,
        is DownloadState.Connecting -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.download_status_connecting), style = MaterialTheme.typography.bodySmall)
            }
        }
        is DownloadState.Retrying -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.download_status_retrying, state.attempt, state.maxRetries),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else -> {}
    }
}

/**
 * Formats ETA seconds into a human-readable string.
 */
@Composable
internal fun formatEta(etaSeconds: Long): String {
    if (etaSeconds < 0) return ""
    if (etaSeconds < 60) return stringResource(R.string.download_eta_less_than_minute)
    val minutes = etaSeconds / 60
    val seconds = etaSeconds % 60
    return if (seconds == 0L) {
        stringResource(R.string.download_eta, "${minutes}m")
    } else {
        stringResource(R.string.download_eta, "${minutes}m ${seconds}s")
    }
}

/**
 * Composable for download rate and ETA display.
 */
@Composable
internal fun DownloadRateEta(state: DownloadState.Downloading) {
    if (state.downloadRateBytesPerSec > 0f) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.download_rate, formatFileSize(state.downloadRateBytesPerSec.toLong())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.etaSeconds >= 0) {
                Text(
                    text = "· ${formatEta(state.etaSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Composable for partial download state with Resume/Clear buttons.
 */
@Composable
internal fun PartialDownloadSection(
    partial: DownloadState.PartiallyDownloaded,
    onResumeClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.download_partial_detected),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.download_partial,
                    partial.progressPercent,
                    formatFileSize(partial.bytesDownloaded),
                    formatFileSize(partial.totalBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResumeClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.download_resume))
                }
                OutlinedButton(
                    onClick = onClearClick
                ) {
                    Text(stringResource(R.string.download_clear_partial))
                }
            }
        }
    }
}
