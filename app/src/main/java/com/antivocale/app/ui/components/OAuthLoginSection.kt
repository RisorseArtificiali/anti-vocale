package com.antivocale.app.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R
import com.antivocale.app.data.HuggingFaceTokenManager
import com.antivocale.app.ui.viewmodel.SettingsViewModel

internal const val HF_TOKEN_SETTINGS_URL = "https://huggingface.co/settings/tokens"

@Composable
fun OAuthLoginSection(
    oauthState: SettingsViewModel.OAuthState,
    tokenState: HuggingFaceTokenManager.TokenState,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    when (oauthState) {
        is SettingsViewModel.OAuthState.Idle -> {
            // Show current token status or login button
            when (val state = tokenState) {
                is HuggingFaceTokenManager.TokenState.Valid -> {
                    var showDetails by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDetails = !showDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = when (state.authType) {
                                    HuggingFaceTokenManager.AuthType.OAUTH -> MaterialTheme.colorScheme.tertiary
                                    HuggingFaceTokenManager.AuthType.MANUAL -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when (state.authType) {
                                    HuggingFaceTokenManager.AuthType.OAUTH -> stringResource(R.string.oauth_connected)
                                    HuggingFaceTokenManager.AuthType.MANUAL -> stringResource(R.string.token_valid)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = when (state.authType) {
                                    HuggingFaceTokenManager.AuthType.OAUTH -> MaterialTheme.colorScheme.tertiary
                                    HuggingFaceTokenManager.AuthType.MANUAL -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Icon(
                                imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showDetails) stringResource(R.string.hide_details) else stringResource(R.string.show_details),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onLogoutClick) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = stringResource(R.string.logout),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (showDetails) {
                        Column(
                            modifier = Modifier.padding(start = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.username_label, state.username),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.token_label, state.maskedToken),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.authType == HuggingFaceTokenManager.AuthType.OAUTH && state.expiresAt != null) {
                                val expiresText = formatExpiration(state.expiresAt, context)
                                val isExpiringSoon = state.needsRefresh()
                                Text(
                                    text = stringResource(R.string.expires_label, expiresText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isExpiringSoon)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                else -> {
                    // No valid token - show login button prominently
                    Button(
                        onClick = onLoginClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.login_with_huggingface))
                    }
                    Text(
                        text = stringResource(R.string.oauth_login_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(HF_TOKEN_SETTINGS_URL)
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.create_account_link))
                    }
                }
            }
        }
        is SettingsViewModel.OAuthState.InProgress -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.authenticating))
            }
        }
        is SettingsViewModel.OAuthState.Success -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.logged_in_as, oauthState.username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is SettingsViewModel.OAuthState.Error -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = oauthState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats expiration timestamp to a human-readable string.
 */
private fun formatExpiration(expiresAt: Long, context: Context): String {
    val now = System.currentTimeMillis()
    val remaining = expiresAt - now

    return when {
        remaining <= 0 -> context.getString(R.string.expired)
        remaining < 60_000 -> "${remaining / 1000}s"
        remaining < 3_600_000 -> "${remaining / 60_000}m"
        remaining < 86_400_000 -> "${remaining / 3_600_000}h"
        else -> "${remaining / 86_400_000}d"
    }
}
