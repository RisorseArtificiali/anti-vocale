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

class HuggingFaceTokenManagerImpl(context: Context) : HuggingFaceTokenManager {

    private val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = createEncryptedPrefs(context, PREFS_FILE_NAME)
    }

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
        private const val KEY_ACCESS_TOKEN = "huggingface_access_token"
        private const val KEY_REFRESH_TOKEN = "huggingface_refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "huggingface_token_expires_at"
        private const val KEY_AUTH_TYPE = "huggingface_auth_type"
    }

    private val _tokenState = MutableStateFlow<HuggingFaceTokenManager.TokenState>(HuggingFaceTokenManager.TokenState.Idle)
    override val tokenState: StateFlow<HuggingFaceTokenManager.TokenState> = _tokenState.asStateFlow()

    override fun saveToken(token: String) {
        try {
            sharedPreferences.edit().putString(KEY_TOKEN, token).apply()
            sharedPreferences.edit().remove(KEY_USERNAME).apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in saveToken: ${e.message}")
        }
    }

    override fun getToken(): String? {
        return sharedPreferences.getString(KEY_TOKEN, null)
    }

    override fun hasToken(): Boolean {
        return try {
            when (val authType = getAuthType()) {
                HuggingFaceTokenManager.AuthType.OAUTH -> {
                    val token = getOAuthAccessToken()
                    val result = !token.isNullOrEmpty()
                    Log.d(TAG, "hasToken check: authType=OAUTH, hasAccessToken=${token != null}, result=$result")
                    result
                }
                HuggingFaceTokenManager.AuthType.MANUAL -> {
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

    override fun clearToken() {
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
        _tokenState.value = HuggingFaceTokenManager.TokenState.Idle
    }

    override fun saveUsername(username: String) {
        sharedPreferences.edit().putString(KEY_USERNAME, username).apply()
    }

    override fun getUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    override fun setTokenState(state: HuggingFaceTokenManager.TokenState) {
        _tokenState.value = state
    }

    override fun getCurrentTokenState(): HuggingFaceTokenManager.TokenState {
        return _tokenState.value
    }

    override fun maskToken(token: String): String {
        return when {
            token.isEmpty() -> ""
            token.length <= 8 -> "${token.take(4)}...${token.takeLast(2)}"
            token.startsWith("hf_") -> {
                val afterPrefix = token.substring(3)
                if (afterPrefix.length <= 4) {
                    "hf_${afterPrefix}..."
                } else {
                    "hf_${afterPrefix.take(3)}...${afterPrefix.takeLast(4)}"
                }
            }
            else -> "${token.take(4)}...${token.takeLast(4)}"
        }
    }

    fun initialize() {
        try {
            val authType = getAuthType()
            when (authType) {
                HuggingFaceTokenManager.AuthType.OAUTH -> {
                    val accessToken = getOAuthAccessToken()
                    val username = getUsername()
                    val expiresAt = getTokenExpiration()

                    if (accessToken != null && username != null) {
                        if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                            Log.i(TAG, "OAuth token has expired, clearing")
                            clearToken()
                        } else {
                            _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(
                                username = username,
                                maskedToken = maskToken(accessToken),
                                authType = HuggingFaceTokenManager.AuthType.OAUTH,
                                expiresAt = expiresAt
                            )
                            Log.i(TAG, "OAuth token restored for user: $username")
                        }
                    }
                }
                HuggingFaceTokenManager.AuthType.MANUAL -> {
                    val token = getToken()
                    val username = getUsername()
                    if (token != null && username != null) {
                        _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(username, maskToken(token))
                        Log.i(TAG, "Manual token restored for user: $username")
                    }
                }
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error during initialize, resetting state: ${e.message}")
            _tokenState.value = HuggingFaceTokenManager.TokenState.Idle
        }
    }

    override fun saveOAuthTokens(
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
                .putString(KEY_AUTH_TYPE, HuggingFaceTokenManager.AuthType.OAUTH.name)
                .apply()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in saveOAuthTokens: ${e.message}")
        }

        _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(
            username = username,
            maskedToken = maskToken(accessToken),
            authType = HuggingFaceTokenManager.AuthType.OAUTH,
            expiresAt = expiresAt
        )

        Log.i(TAG, "OAuth tokens saved for user: $username, expires at: $expiresAt")
    }

    override fun getOAuthAccessToken(): String? {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    override fun getRefreshToken(): String? {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun getTokenExpiration(): Long? {
        val expiresAt = sharedPreferences.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        return if (expiresAt > 0L) expiresAt else null
    }

    override fun getAuthType(): HuggingFaceTokenManager.AuthType {
        val typeString = sharedPreferences.getString(KEY_AUTH_TYPE, null)
        return try {
            typeString?.let { HuggingFaceTokenManager.AuthType.valueOf(it) } ?: HuggingFaceTokenManager.AuthType.MANUAL
        } catch (e: IllegalArgumentException) {
            HuggingFaceTokenManager.AuthType.MANUAL
        }
    }

    override fun isOAuthToken(): Boolean {
        return getAuthType() == HuggingFaceTokenManager.AuthType.OAUTH
    }

    override fun needsTokenRefresh(): Boolean {
        val state = _tokenState.value
        return state is HuggingFaceTokenManager.TokenState.Valid &&
            state.authType == HuggingFaceTokenManager.AuthType.OAUTH &&
            state.needsRefresh()
    }

    override fun isTokenExpired(): Boolean {
        val state = _tokenState.value
        return state is HuggingFaceTokenManager.TokenState.Valid &&
            state.authType == HuggingFaceTokenManager.AuthType.OAUTH &&
            state.isExpired()
    }

    override fun updateAccessToken(accessToken: String, expiresAt: Long) {
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

        _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(
            username = username,
            maskedToken = maskToken(accessToken),
            authType = HuggingFaceTokenManager.AuthType.OAUTH,
            expiresAt = expiresAt
        )

        Log.i(TAG, "Access token refreshed, new expiration: $expiresAt")
    }

    override fun getEffectiveToken(): String? {
        return try {
            when (getAuthType()) {
                HuggingFaceTokenManager.AuthType.OAUTH -> getOAuthAccessToken()
                HuggingFaceTokenManager.AuthType.MANUAL -> getToken()
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "KeyStore error in getEffectiveToken: ${e.message}")
            null
        }
    }
}
