package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.transcription.Language

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageFilterBar(
    selectedLanguageCode: String?,
    onLanguageSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }

    val selectedLabel = selectedLanguageCode?.let { code ->
        Language.FILTER_ENTRIES.find { it.code == code }?.let {
            stringResource(it.nameResId)
        }
    } ?: stringResource(R.string.lang_filter_all)

    val searchQuery = textFieldValue.text

    val allEntries = Language.FILTER_ENTRIES
        .map { it to stringResource(it.nameResId) }
        .sortedBy { (_, name) -> name }

    val matchedEntries = remember(searchQuery) {
        if (searchQuery.isBlank()) allEntries
        else allEntries.filter { (_, name) ->
            name.contains(searchQuery, ignoreCase = true)
        }
    }

    val displayValue = if (expanded) textFieldValue
        else TextFieldValue(selectedLabel, TextRange(selectedLabel.length))

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (it) {
                textFieldValue = TextFieldValue()
                expanded = true
            } else {
                expanded = false
                textFieldValue = TextFieldValue()
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {
                textFieldValue = it
                if (!expanded) expanded = true
            },
            label = { Text(stringResource(R.string.lang_filter_label)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp)
                )
            },
            trailingIcon = if (expanded && textFieldValue.text.isNotEmpty()) {
                {
                    IconButton(onClick = { textFieldValue = TextFieldValue() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.lang_filter_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                textFieldValue = TextFieldValue()
            }
        ) {
            if (searchQuery.isBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lang_filter_all)) },
                    onClick = {
                        expanded = false
                        textFieldValue = TextFieldValue()
                        onLanguageSelected(null)
                    }
                )
            }

            if (matchedEntries.isEmpty() && textFieldValue.text.isNotBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.lang_filter_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {},
                    enabled = false
                )
            } else {
                matchedEntries.forEach { (entry, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            textFieldValue = TextFieldValue()
                            onLanguageSelected(entry.code)
                        }
                    )
                }
            }
        }
    }
}
