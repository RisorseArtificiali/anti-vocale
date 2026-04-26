package com.antivocale.app.data

import kotlinx.coroutines.flow.StateFlow

interface HuggingFaceTokenManager {

    val tokenState: StateFlow<TokenState>

    fun saveToken(token: String)
    fun getToken(): String?
    fun hasToken(): Boolean
    fun clearToken()
    fun saveUsername(username: String)
    fun getUsername(): String?
    fun setTokenState(state: TokenState)
    fun getCurrentTokenState(): TokenState
    fun maskToken(token: String): String

    fun saveOAuthTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        username: String
    )
    fun getOAuthAccessToken(): String?
    fun getRefreshToken(): String?
    fun getTokenExpiration(): Long?
    fun getAuthType(): AuthType
    fun isOAuthToken(): Boolean
    fun needsTokenRefresh(): Boolean
    fun isTokenExpired(): Boolean
    fun updateAccessToken(accessToken: String, expiresAt: Long)
    fun getEffectiveToken(): String?

    enum class AuthType {
        MANUAL,
        OAUTH
    }

    sealed class TokenState {
        data object Idle : TokenState()
        data object Validating : TokenState()

        data class Valid(
            val username: String,
            val maskedToken: String,
            val authType: AuthType = AuthType.MANUAL,
            val expiresAt: Long? = null
        ) : TokenState() {
            fun needsRefresh(): Boolean {
                return expiresAt != null && System.currentTimeMillis() > (expiresAt - REFRESH_BUFFER_MS)
            }

            fun isExpired(): Boolean {
                return expiresAt != null && System.currentTimeMillis() >= expiresAt
            }

            companion object {
                private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
            }
        }

        data class Invalid(val error: String) : TokenState()
    }
}
