package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.antivocale.app.R

/**
 * TASK-605 (c): the shared search field (Settings migrated; History keeps
 * its dual-purpose trailing slot as a documented override; the language
 * filter stays inline: an editable dropdown, a different widget family).
 * Blank value = no clear button.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    @androidx.annotation.StringRes placeholderRes: Int,
    modifier: Modifier = Modifier,
    contentDescriptionRes: Int = R.string.clear_search,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(placeholderRes)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(contentDescriptionRes)
                    )
                }
            }
        } else null,
        singleLine = true,
        // TASK-564: matches the Models tab's language filter field
        // (shapes.medium = 12dp), not the extraLarge pill.
        shape = MaterialTheme.shapes.medium,
    )
}
