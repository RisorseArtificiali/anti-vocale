package com.antivocale.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.GeneralSecurityException

/**
 * Manages secure storage and validation of HuggingFace tokens.
 *
 * Supports two authentication methods:
 * 1. **Manual Token**: User-provided HuggingFace access token (hf_...)
 * 2. **OAuth Token**: Tokens obtained via OAuth flow (access + refresh + expiration)
 *
 * Uses EncryptedSharedPreferences to store tokens securely on device.
 * Provides token state flow for UI observation and masking for display.
 *
 * Handles KeyStore corruption gracefully: if the MasterKey becomes invalid
 * (due to OS updates, security patches, or device secure element resets),
 * the encrypted prefs are wiped and a new key is generated. The user will
 * need to re-authenticate, but the app remains functional.
 */
class HuggingFaceTokenManager(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = createEncryptedPrefs(context, PREFS_FILE_NAME)
    }

    /**
     * Creates EncryptedSharedPreferences with recovery from KeyStore corruption.
     *
     * If the MasterKey or encrypted prefs are in an unrecoverable state
     * (e.g., KeyStoreException: Signature/MAC verification failed), this
     * method deletes the corrupted prefs file and key, then recreates them
     * from scratch. Tokens are lost but the app remains functional.
     */
    private fun createEncryptedPrefs(
        context: Context,
        prefsFileName: String
    ): SharedPreferences {
        return try {
            buildEncryptedPrefs(context, prefsFileName)
        } catch (e: Exception) {
            Log.e(TAG, "KeyStore error during init, recovering: ${e.message}", e)
            deleteEncryptedPrefs(context, prefsFileName)
            try {
                Log.w(TAG, "Encrypted prefs recreated after KeyStore recovery — tokens lost")
                buildEncryptedPrefs(context, prefsFileName)
            } catch (e2: Exception) {
                Log.e(TAG, "KeyStore still broken after cleanup, falling back to plain prefs: ${e2.message}", e2)
                context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(
        context: Context,
        prefsFileName: String
    ): SharedPreferences {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            prefsFileName,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Deletes the encrypted prefs XML file and attempts to remove the MasterKey
     * from the Android KeyStore so it can be regenerated cleanly.
     */
    private fun deleteEncryptedPrefs(context: Context, prefsFileName: String) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.filter { it.name.startsWith(prefsFileName) }?.forEach { file ->
            if (file.delete()) {
                Log.i(TAG, "Deleted corrupted prefs file: ${file.name}")
            }
        }

        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            Log.i(TAG, "Deleted corrupted MasterKey from KeyStore")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete MasterKey from KeyStore: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HuggingFaceTokenManager"
        private const val PREFS_FILE_NAME = "huggingface_encrypted_prefs"
        private const val KEY_TOKEN = "huggingface_token"
        private const val KEY_USERNAME = "huggingface_username"

        // OAuth-specific keys
        private const val KEY_ACCESS_TOKEN = "huggingface_access_token"
        private const val KEY_REFRESH_TOKEN = "huggingface_refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "huggingface_token_expires_at"
        private const val KEY_AUTH_TYPE = "huggingface_auth_type"

        // Token refresh buffer: refresh 5 minutes before expiration
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
    }

    // Token state flow
    private val _tokenState = MutableStateFlow<TokenState>(TokenState.Idle)
    val tokenState: StateFlow<TokenState> = _tokenState.asStateFlow()

    /**
     * Authentication type - indicates how the token was obtained.
     */
    enum class AuthType {
        /** Manually entered HuggingFace token */
        MANUAL,
        /** Obtained via OAuth flow */
        OAUTH
    }

    /**
     * Represents the current state of the HuggingFace token.
     */
    sealed class TokenState {
        /** No token operation in progress */
        data object Idle : TokenState()

        /** Token is being validated */
        data object Validating : TokenState()

        /** Token is valid with associated username */
        data class Valid(
            val username: String,
            val maskedToken: String,
            val authType: AuthType = AuthType.MANUAL,
            val expiresAt: Long? = null
        ) : TokenState() {
            /**
             * Checks if the token needs refresh (for OAuth tokens).
             * Returns true if token expires within the refresh buffer window.
             */
            fun needsRefresh(): Boolean {
                return expiresAt != null && System.currentTimeMillis() > (expiresAt - REFRESH_BUFFER_MS)
            }

            /**
             * Checks if the token has expired.
             */
            fun isExpired(): Boolean {
                return expiresAt != null && System.currentTimeMillis() >= expiresAt
            }
        }

        /** Token is invalid or validation failed */
        data class Invalid(val error: String) : TokenState()
    }

    /**
     * Saves the HuggingFace token to encrypted storage.
     */
    fun saveToken(token: String) {
        try {
            sharedPreferences.edit().putString(KEY_TOKEN, token).apply()
            sharedPreferences.edit().remove(KEY_USERNAME).apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in saveToken: ${e.message}")
        }
    }

    /**
     * Retrieves the stored HuggingFace token.
     * @return The token string, or null if not stored.
     */
    fun getToken(): String? {
        return sharedPreferences.getString(KEY_TOKEN, null)
    }

    /**
     * Checks if a token is stored (either manual PAT or OAuth).
     */
    fun hasToken(): Boolean {
        return try {
            val authType = getAuthType()
            when (authType) {
                AuthType.OAUTH -> {
                    val token = getOAuthAccessToken()
                    val result = !token.isNullOrEmpty()
                    Log.d(TAG, "hasToken check: authType=OAUTH, hasAccessToken=${token != null}, result=$result")
                    result
                }
                AuthType.MANUAL -> {
                    val token = getToken()
                    val result = !token.isNullOrEmpty()
                    Log.d(TAG, "hasToken check: authType=MANUAL, hasToken=${token != null}, result=$result")
                    result
                }
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in hasToken: ${e.message}")
            false
        }
    }

    /**
     * Clears the stored token and username.
     */
    fun clearToken() {
        try {
            sharedPreferences.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USERNAME)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_TOKEN_EXPIRES_AT)
                .remove(KEY_AUTH_TYPE)
                .apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in clearToken: ${e.message}")
        }
        _tokenState.value = TokenState.Idle
    }

    /**
     * Saves the username associated with the validated token.
     */
    fun saveUsername(username: String) {
        sharedPreferences.edit().putString(KEY_USERNAME, username).apply()
    }

    /**
     * Retrieves the stored username.
     */
    fun getUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    /**
     * Updates the token state flow.
     */
    fun setTokenState(state: TokenState) {
        _tokenState.value = state
    }

    /**
     * Gets the current token state.
     */
    fun getCurrentTokenState(): TokenState {
        return _tokenState.value
    }

    /**
     * Masks the token for display purposes using a condensed format.
     * Examples: `hf_abc...i789`, `hf_sho...ort`, `abc...123`
     */
    fun maskToken(token: String): String {
        return when {
            token.isEmpty() -> ""
            token.length <= 8 -> "${token.take(4)}...${token.takeLast(2)}"
            token.startsWith("hf_") -> {
                // For HuggingFace tokens: show "hf_" + 3 chars + "..." + last 4 chars
                val afterPrefix = token.substring(3)
                if (afterPrefix.length <= 4) {
                    "hf_${afterPrefix}..."
                } else {
                    "hf_${afterPrefix.take(3)}...${afterPrefix.takeLast(4)}"
                }
            }
            else -> {
                // For other tokens: show first 4 + "..." + last 4
                "${token.take(4)}...${token.takeLast(4)}"
            }
        }
    }

    /**
     * Initializes the token state on app startup.
     * Checks for existing token and username to restore Valid state.
     *
     * Wrapped in try-catch so that a Keystore corruption at runtime
     * doesn't crash the app — the user simply sees no token and
     * is prompted to re-authenticate.
     */
    fun initialize() {
        try {
            val authType = getAuthType()
            when (authType) {
                AuthType.OAUTH -> {
                    val accessToken = getOAuthAccessToken()
                    val username = getUsername()
                    val expiresAt = getTokenExpiration()

                    if (accessToken != null && username != null) {
                        if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                            Log.i(TAG, "OAuth token has expired, clearing")
                            clearToken()
                        } else {
                            _tokenState.value = TokenState.Valid(
                                username = username,
                                maskedToken = maskToken(accessToken),
                                authType = AuthType.OAUTH,
                                expiresAt = expiresAt
                            )
                            Log.i(TAG, "OAuth token restored for user: $username")
                        }
                    }
                }
                AuthType.MANUAL -> {
                    val token = getToken()
                    val username = getUsername()
                    if (token != null && username != null) {
                        _tokenState.value = TokenState.Valid(username, maskToken(token))
                        Log.i(TAG, "Manual token restored for user: $username")
                    }
                }
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error during initialize, resetting state: ${e.message}")
            _tokenState.value = TokenState.Idle
        }
    }

    // ==================== OAuth Token Management ====================

    /**
     * Saves OAuth tokens obtained from the OAuth flow.
     *
     * @param accessToken The access token for API calls
     * @param refreshToken The refresh token for obtaining new access tokens
     * @param expiresAt The expiration timestamp in milliseconds
     * @param username The authenticated user's name
     */
    fun saveOAuthTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        username: String
    ) {
        try {
            sharedPreferences.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
                .putString(KEY_USERNAME, username)
                .putString(KEY_AUTH_TYPE, AuthType.OAUTH.name)
                .apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in saveOAuthTokens: ${e.message}")
        }

        _tokenState.value = TokenState.Valid(
            username = username,
            maskedToken = maskToken(accessToken),
            authType = AuthType.OAUTH,
            expiresAt = expiresAt
        )

        Log.i(TAG, "OAuth tokens saved for user: $username, expires at: $expiresAt")
    }

    /**
     * Gets the OAuth access token.
     * Returns null if no OAuth token is stored.
     */
    fun getOAuthAccessToken(): String? {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Gets the OAuth refresh token.
     * Returns null if no OAuth token is stored.
     */
    fun getRefreshToken(): String? {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Gets the token expiration timestamp in milliseconds.
     * Returns null if not set or for manual tokens.
     */
    fun getTokenExpiration(): Long? {
        val expiresAt = sharedPreferences.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        return if (expiresAt > 0L) expiresAt else null
    }

    /**
     * Gets the authentication type.
     */
    fun getAuthType(): AuthType {
        val typeString = sharedPreferences.getString(KEY_AUTH_TYPE, null)
        return try {
            typeString?.let { AuthType.valueOf(it) } ?: AuthType.MANUAL
        } catch (e: IllegalArgumentException) {
            AuthType.MANUAL
        }
    }

    /**
     * Checks if the current token is from OAuth.
     */
    fun isOAuthToken(): Boolean {
        return getAuthType() == AuthType.OAUTH
    }

    /**
     * Checks if the token needs to be refreshed.
     * Only applies to OAuth tokens.
     */
    fun needsTokenRefresh(): Boolean {
        val state = _tokenState.value
        return state is TokenState.Valid && state.authType == AuthType.OAUTH && state.needsRefresh()
    }

    /**
     * Checks if the token has expired.
     * Only applies to OAuth tokens.
     */
    fun isTokenExpired(): Boolean {
        val state = _tokenState.value
        return state is TokenState.Valid && state.authType == AuthType.OAUTH && state.isExpired()
    }

    /**
     * Updates only the access token after a refresh.
     * Keeps the refresh token and other OAuth data.
     */
    fun updateAccessToken(accessToken: String, expiresAt: Long) {
        val username = try {
            getUsername() ?: return
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error reading username in updateAccessToken: ${e.message}")
            return
        }

        try {
            sharedPreferences.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
                .apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in updateAccessToken: ${e.message}")
        }

        _tokenState.value = TokenState.Valid(
            username = username,
            maskedToken = maskToken(accessToken),
            authType = AuthType.OAUTH,
            expiresAt = expiresAt
        )

        Log.i(TAG, "Access token refreshed, new expiration: $expiresAt")
    }

    /**
     * Gets the effective token for API calls.
     * Returns the OAuth access token if available, otherwise the manual token.
     */
    fun getEffectiveToken(): String? {
        return try {
            when (getAuthType()) {
                AuthType.OAUTH -> getOAuthAccessToken()
                AuthType.MANUAL -> getToken()
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in getEffectiveToken: ${e.message}")
            null
        }
    }
}
