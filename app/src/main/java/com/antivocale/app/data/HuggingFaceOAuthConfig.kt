package com.antivocale.app.data

import android.net.Uri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * OAuth configuration for HuggingFace authentication.
 *
 * This configuration enables the AppAuth-Android library to authenticate
 * users with HuggingFace via OAuth 2.0 Authorization Code Flow with PKCE.
 *
 * ## Setup Instructions
 *
 * 1. Go to https://huggingface.co/settings/oauth/apps
 * 2. Click "New OAuth Application"
 * 3. Configure:
 *    - **Application name**: Anti-Vocale (or your preferred name)
 *    - **Homepage URL**: https://github.com/paoloantinori/anti-vocale
 *    - **Redirect URI**: com.antivocale.app://oauth2callback
 *    - **Scopes**: read-repos (for model downloads)
 * 4. Copy the **Client ID** and update CLIENT_ID below
 *
 * ## Why OAuth?
 *
 * OAuth provides a smoother UX than manual token entry:
 * - Chrome Custom Tabs share session cookies with Chrome browser
 * - Users already logged into HuggingFace in Chrome get instant auth
 * - No need to manually copy/paste tokens
 * - Automatic token refresh before expiration
 */
object HuggingFaceOAuthConfig {

    /**
     * HuggingFace OAuth Client ID.
     *
     * Obtained from https://huggingface.co/settings/oauth-apps
     */
    const val CLIENT_ID = "982f691f-f8d2-4b1d-a5aa-0b33c7b1bb50"

    /**
     * Redirect URI for OAuth callback.
     * Must match the scheme configured in build.gradle.kts manifestPlaceholders.
     *
     * Format: {appAuthRedirectScheme}://oauth2callback
     */
    const val REDIRECT_URI = "com.antivocale.app://oauth2callback"

    /**
     * OAuth scopes requested from HuggingFace.
     *
     * - `read-repos`: Read access to models (required for downloading gated models)
     *
     * See: https://huggingface.co/docs/hub/oauth
     */
    const val SCOPE = "read-repos"

    /**
     * HuggingFace OAuth endpoints.
     */
    private const val AUTH_ENDPOINT = "https://huggingface.co/oauth/authorize"
    private const val TOKEN_ENDPOINT = "https://huggingface.co/oauth/token"

    /**
     * AppAuth service configuration for HuggingFace.
     *
     * This configuration is used to create authorization and token requests.
     */
    val serviceConfig: AuthorizationServiceConfiguration by lazy {
        AuthorizationServiceConfiguration(
            Uri.parse(AUTH_ENDPOINT),
            Uri.parse(TOKEN_ENDPOINT)
        )
    }

    /**
     * Validates that the OAuth configuration is properly set up.
     *
     * @return true if configuration is valid, false if CLIENT_ID needs to be set
     */
    fun isConfigured(): Boolean {
        return CLIENT_ID != "YOUR_CLIENT_ID_HERE" && CLIENT_ID.isNotBlank()
    }

    /**
     * Gets the configuration status message for UI display.
     */
    fun getConfigStatus(): ConfigStatus {
        return when {
            !isConfigured() -> ConfigStatus.NotConfigured(
                "OAuth not configured. Please set up a HuggingFace OAuth app and update CLIENT_ID."
            )
            else -> ConfigStatus.Configured
        }
    }

    /**
     * Configuration status sealed class.
     */
    sealed class ConfigStatus {
        data object Configured : ConfigStatus()
        data class NotConfigured(val message: String) : ConfigStatus()
    }
}
