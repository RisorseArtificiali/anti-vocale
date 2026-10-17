package com.antivocale.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TASK-756: the ONE read-only dropdown field, with the one-line constraint
 * owned at the component layer. The guard is a REGRESSION guard, not a
 * style choice: TASK-755, the maintainer's own report of a wrapped value
 * growing the field vertically, is the incident it prevents. A long
 * translated value used to grow these
 * fields vertically at every hand-rolled site (ModelTab's family, CTC
 * subtype, language and Canary selectors, the per-app sound selector); the
 * constraint now lives here, so the next long locale string is a non-event.
 *
 * The caller owns the ExposedDropdownMenuBox and passes the modifier from
 * INSIDE its scope (menuAnchor is scope-restricted): `modifier =
 * Modifier.fillMaxWidth().menuAnchor()`.
 *
 * The filled counterpart is SettingsDropdown (a whole select with the
 * TASK-592 tonal container and its own value-row guard): two visual
 * variants on purpose, do not unify them.
 *
 * Ellipsis vs clip: material3 (1.4.0, the resolved BOM pin) exposes no
 * textOverflow on the field (TextStyle.copy has no overflow parameter
 * either), so singleLine's default CLIP is today's behavior; when a
 * Compose bump exposes the overflow parameter, the maintainer's
 * clip-vs-ellipsis choice lands HERE, once, for every site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadOnlyDropdownField(
    value: String,
    label: @Composable () -> Unit,
    /** The box's open state, for the trailing icon's rotation only; the
     *  open/close toggle stays the caller's ExposedDropdownMenuBox. */
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = label,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        singleLine = true,
        modifier = modifier,
    )
}
