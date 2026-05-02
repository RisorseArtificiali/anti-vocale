package com.antivocale.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.ui.viewmodel.SettingsViewModel
import com.antivocale.app.util.ToastCompat

@Composable
fun PromptSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val defaultPrompt by viewModel.defaultPrompt.collectAsState()
    var promptInput by remember { mutableStateOf(defaultPrompt) }
    val context = LocalContext.current

    LaunchedEffect(defaultPrompt) {
        promptInput = defaultPrompt
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with back button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(
                text = stringResource(R.string.default_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // Description
        Text(
            text = stringResource(R.string.default_prompt_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Current default info
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.current_default_prompt_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = defaultPrompt.ifEmpty { stringResource(R.string.builtin_default_prompt) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (defaultPrompt.isEmpty()) FontStyle.Italic else FontStyle.Normal
                )
            }
        }

        // Compatibility info banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.default_prompt_compatibility_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Prompt input field
        OutlinedTextField(
            value = promptInput,
            onValueChange = { newValue ->
                if (newValue.length <= 500) {
                    promptInput = newValue
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.default_prompt_placeholder)) },
            minLines = 3,
            maxLines = 6,
            supportingText = {
                Text(
                    text = stringResource(R.string.default_prompt_chars, promptInput.length),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            trailingIcon = {
                if (promptInput != defaultPrompt) {
                    IconButton(onClick = {
                        viewModel.saveDefaultPrompt(promptInput)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            }
        )

        HorizontalDivider()

        // Example Prompts Section
        Text(
            text = stringResource(R.string.example_prompts_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.example_prompts_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val examplePrompts = listOf(
            R.string.example_prompt_transcribe,
            R.string.example_prompt_summarize,
            R.string.example_prompt_formal,
            R.string.example_prompt_translate_en,
            R.string.example_prompt_translate_it,
            R.string.example_prompt_notes
        )

        examplePrompts.forEach { promptResId ->
            val promptText = stringResource(promptResId)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        promptInput = promptText
                        viewModel.saveDefaultPrompt(promptText)
                        ToastCompat.show(
                            context,
                            context.getString(R.string.prompt_applied)
                        )
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (defaultPrompt == promptText)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (defaultPrompt == promptText) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
