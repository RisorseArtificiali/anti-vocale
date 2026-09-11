package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager

@Composable
fun EditablePromptCard(
    prompt: String,
    onSave: (String) -> Unit,
    titleRes: Int,
    descriptionRes: Int,
    placeholderRes: Int,
) {
    var text by remember(prompt) { mutableStateOf(prompt) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(PreferencesManager.PROMPT_CAP) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused ->
                        if (!focused.isFocused && text != prompt) onSave(text)
                    },
                placeholder = { Text(stringResource(placeholderRes)) },
                minLines = 2,
                supportingText = {
                    Text(stringResource(R.string.default_prompt_chars, text.length))
                }
            )
        }
    }
}
