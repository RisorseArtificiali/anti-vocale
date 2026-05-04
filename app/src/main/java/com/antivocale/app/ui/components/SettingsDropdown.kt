package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A reusable dropdown selector for settings screens.
 *
 * Wraps ExposedDropdownMenuBox with a read-only TextField and a menu of options.
 * Each option displays a check icon when selected.
 *
 * @param T the type of option values
 * @param currentValue the currently selected value (used for check icon comparison)
 * @param options the list of selectable options
 * @param currentValueDisplay the text to display in the TextField for the current value
 * @param optionDisplay maps an option to its display text in the dropdown menu
 * @param onOptionSelected called when the user selects an option
 * @param label the label text shown above the TextField
 * @param enabled whether the dropdown is interactive
 * @param optionKey extracts a comparison key from an option and the current value,
 *   used to determine which option gets the check icon. Defaults to [Any.equals].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsDropdown(
    currentValue: T,
    options: List<T>,
    currentValueDisplay: String,
    optionDisplay: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionKey: (T) -> Any = { it as Any }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        TextField(
            value = currentValueDisplay,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            enabled = enabled,
            colors = ExposedDropdownMenuDefaults.textFieldColors(
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionDisplay(option)) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    trailingIcon = if (optionKey(currentValue) == optionKey(option)) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
        }
    }
}
