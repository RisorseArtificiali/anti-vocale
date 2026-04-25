package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
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
    var searchQuery by remember { mutableStateOf("") }

    val selectedLabel = selectedLanguageCode?.let { code ->
        Language.FILTER_ENTRIES.find { it.code == code }?.let {
            stringResource(it.nameResId)
        }
    } ?: stringResource(R.string.lang_filter_all)

    val allEntries = Language.FILTER_ENTRIES
        .map { it to stringResource(it.nameResId) }
        .sortedBy { (_, name) -> name }

    val matchedEntries = remember(searchQuery) {
        if (searchQuery.isBlank()) allEntries
        else allEntries.filter { (_, name) ->
            name.contains(searchQuery, ignoreCase = true)
        }
    }

    val displayText = if (expanded) searchQuery else selectedLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (it) {
                searchQuery = ""
                expanded = true
            } else {
                expanded = false
                searchQuery = ""
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {
                searchQuery = it
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
                searchQuery = ""
            }
        ) {
            if (searchQuery.isBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lang_filter_all)) },
                    onClick = {
                        onLanguageSelected(null)
                        expanded = false
                        searchQuery = ""
                    }
                )
            }

            if (matchedEntries.isEmpty() && searchQuery.isNotBlank()) {
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
                            onLanguageSelected(entry.code)
                            expanded = false
                            searchQuery = ""
                        }
                    )
                }
            }
        }
    }
}
