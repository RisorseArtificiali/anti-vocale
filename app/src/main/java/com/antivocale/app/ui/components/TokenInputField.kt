package com.antivocale.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.content.ClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.antivocale.app.R

@Composable
fun TokenInputField(
    value: String,
    onValueChange: (String) -> Unit,
    tokenPasswordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.huggingface_token_label)) },
        placeholder = { Text(stringResource(R.string.token_placeholder)) },
        singleLine = true,
        visualTransformation = if (tokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = {
                    clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.let {
                        onValueChange(it)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.paste_from_clipboard)
                    )
                }
                IconButton(onClick = onPasswordVisibilityToggle) {
                    Icon(
                        imageVector = if (tokenPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (tokenPasswordVisible) stringResource(R.string.hide_token) else stringResource(R.string.show_token)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier,
        enabled = enabled,
        isError = isError,
    )
}
