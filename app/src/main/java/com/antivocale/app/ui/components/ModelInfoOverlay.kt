package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.transcription.ArchitectureType
import com.antivocale.app.transcription.LanguageFlags
import com.antivocale.app.transcription.ModelInfo
import com.antivocale.app.transcription.ModelVariant
import com.antivocale.app.util.LanguageNames
import com.antivocale.app.util.formatFileSize

@Composable
fun ModelInfoOverlay(
    variant: ModelVariant,
    info: ModelInfo?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = {
            Text(stringResource(variant.titleResId))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LanguagesSection(variant.supportedLanguageCodes)

                HorizontalDivider()

                if (info != null) {
                    RecommendationsSection(info)
                    HorizontalDivider()
                    TechnicalDetailsSection(variant, info)
                    HorizontalDivider()
                    PerformanceTipsSection(info)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.model_info_close))
            }
        }
    )
}

private data class LanguageItem(
    val code: String,
    val name: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesSection(languageCodes: Set<String>) {
    SectionHeader(
        icon = { Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp)) },
        title = if (languageCodes.isNotEmpty()) {
            pluralStringResource(R.plurals.model_info_languages_count, languageCodes.size, languageCodes.size)
        } else {
            stringResource(R.string.model_info_no_languages)
        }
    )

    if (languageCodes.isEmpty()) return

    // TASK-796 trial 2b (maintainer): sort by the DISPLAYED endonym with
    // the ACTIVE APP locale's collation (the entriesByEndonym discipline,
    // TASK-353/790). The old sort keyed on hidden English names, so the
    // chips read out of order in every locale.
    val appLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val items = remember(languageCodes, appLocale) {
        val collator = java.text.Collator.getInstance(appLocale)
        collator.strength = java.text.Collator.PRIMARY
        languageCodes
            .map { code -> LanguageItem(code, LanguageNames.nativeLanguageName(code)) }
            .sortedWith(compareBy(collator) { it.name })
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { item ->
            LanguageChip(
                flag = LanguageFlags.getFlag(item.code),
                name = item.name
            )
        }
    }
}

@Composable
private fun LanguageChip(flag: String, name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (flag.isNotEmpty()) {
                Text(text = flag, style = MaterialTheme.typography.labelMedium)
            }
            Text(text = name, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RecommendationsSection(info: ModelInfo) {
    SectionHeader(
        icon = { Icon(Icons.Default.TipsAndUpdates, contentDescription = null, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.model_info_section_recommendations)
    )

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = stringResource(info.bestFor),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(info.performanceNotes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TechnicalDetailsSection(variant: ModelVariant, info: ModelInfo) {
    SectionHeader(
        icon = { Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.model_info_section_technical)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow(
            stringResource(R.string.model_info_architecture),
            when (info.architectureType) {
                ArchitectureType.ENCODER_DECODER -> stringResource(R.string.model_info_arch_encoder_decoder)
                ArchitectureType.TRANSDUCER -> stringResource(R.string.model_info_arch_transducer)
                ArchitectureType.ENCODER_ONLY_CTC -> stringResource(R.string.model_info_arch_ctc)
                ArchitectureType.LLM -> stringResource(R.string.model_info_arch_llm)
            }
        )

        InfoRow(
            stringResource(R.string.model_info_model_size),
            formatFileSize(variant.estimatedSizeMB * 1024 * 1024)
        )

        info.quantizationLevel?.let { quant ->
            InfoRow(stringResource(R.string.model_info_quantization), quant)
        }

        InfoRow(
            stringResource(R.string.model_info_recommended_threads),
            stringResource(R.string.model_info_threads_range, info.recommendedThreads.first, info.recommendedThreads.last)
        )

        if (info.isArm64Only) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "⚠ " + stringResource(R.string.model_info_arm64_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PerformanceTipsSection(info: ModelInfo) {
    SectionHeader(
        icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
        title = stringResource(R.string.model_info_section_performance)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        info.benchmarkWer?.let { wer ->
            Text(
                text = stringResource(R.string.model_info_benchmark_wer, wer),
                style = MaterialTheme.typography.bodySmall
            )
        }

        info.relativeSpeed?.let { speed ->
            Text(
                text = stringResource(R.string.model_info_relative_speed, speed),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (info.vadRecommended) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✓ ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.model_info_vad_recommended),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (info.supportsProgressiveTranscription) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✓ ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.model_info_progressive),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: @Composable () -> Unit, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // TASK-386 (WCAG 1.4.4): fixed dp clips long localized labels at 200%
            // font scale; weight lets the label column shrink and wrap.
            modifier = Modifier.weight(0.45f, fill = false)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            // TASK-386: without a weight the value measures with the FULL row
            // width first and squeezes the weighted label (reviewer finding).
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
