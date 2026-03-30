package com.antivocale.app.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.antivocale.app.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages HuggingFace OAuth authentication flow using AppAuth-Android.
 *
 * This class handles:
 * - Creating authorization requests
 * - Launching Chrome Custom Tabs for authentication
 * - Exchanging authorization codes for tokens
 * - Refreshing expired tokens
 *
 * ## Usage
 *
 * ```kotlin
 * val authManager = HuggingFaceAuthManager(context, tokenManager)
 *
 * // Start OAuth flow
 * authManager.startAuthFlow(activity, authLauncher)
 *
 * // Handle callback
 * val result = authManager.handleAuthResult(result.data)
 * ```
 *
 * ## Why Chrome Custom Tabs?
 *
 * Chrome Custom Tabs provide a smooth UX because they share session cookies
 * with the Chrome browser. Users already logged into HuggingFace in Chrome
 * will see a brief flash of the browser before being redirected back to the app.
 */
class HuggingFaceAuthManager(
    private val context: Context,
    private val huggingFaceTokenManager: HuggingFaceTokenManager
) {

    private var authService: AuthorizationService? = null
    private var pendingTokenExchange: ((TokenResult) -> Unit)? = null

    companion object {
        private const val TAG = "HuggingFaceAuthManager"
    }

    /**
     * Result of token exchange operation.
     */
    sealed class TokenResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresAt: Long,
            val username: String
        ) : TokenResult()

        data class Error(val message: String, val exception: Exception? = null) : TokenResult()
    }

    /**
     * Result of authorization flow.
     */
    sealed class AuthResult {
        data class Success(val username: String) : AuthResult()
        data class Cancelled(val reason: String = "User cancelled") : AuthResult()
        data class Error(val message: String, val exception: Exception? = null) : AuthResult()
    }

    /**
     * Checks if OAuth is properly configured.
     */
    fun isConfigured(): Boolean {
        return HuggingFaceOAuthConfig.isConfigured()
    }

    /**
     * Creates an authorization request for HuggingFace OAuth.
     */
    private fun createAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            HuggingFaceOAuthConfig.serviceConfig,
            HuggingFaceOAuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(HuggingFaceOAuthConfig.REDIRECT_URI)
        )
            .setScope(HuggingFaceOAuthConfig.SCOPE)
            // Use 'consent' prompt to ensure we get a refresh token
            // and to allow re-authentication after token revocation
            .setPrompt("consent")
            .build()
    }

    /**
     * Starts the OAuth authentication flow.
     *
     * This launches a Chrome Custom Tab for the user to authenticate with HuggingFace.
     * The result will be delivered to the provided launcher.
     *
     * @param activity The activity context (for launching the intent)
     * @param launcher The ActivityResultLauncher registered for OAuth callbacks
     */
    fun startAuthFlow(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>
    ) {
        if (!isConfigured()) {
            Log.e(TAG, "OAuth not configured - CLIENT_ID not set")
            throw IllegalStateException("OAuth not configured. Please set CLIENT_ID in HuggingFaceOAuthConfig.")
        }

        // Create or reuse the authorization service
        if (authService == null) {
            authService = AuthorizationService(context)
        }

        val authRequest = createAuthorizationRequest()
        val authIntent = authService!!.getAuthorizationRequestIntent(authRequest)

        Log.i(TAG, "Starting OAuth flow with redirect: ${HuggingFaceOAuthConfig.REDIRECT_URI}")
        launcher.launch(authIntent)
    }

    /**
     * Handles the OAuth callback result.
     *
     * This should be called from the ActivityResult callback with the intent data.
     * It exchanges the authorization code for tokens.
     *
     * @param data The intent data from the OAuth callback
     * @param onResult Callback for the authentication result
     */
    fun handleAuthResult(data: Intent?, onResult: (AuthResult) -> Unit) {
        if (data == null) {
            Log.w(TAG, "Auth result data is null")
            onResult(AuthResult.Cancelled("No result data"))
            return
        }

        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        when {
            response != null -> {
                Log.i(TAG, "Authorization successful, exchanging code for tokens")
                exchangeCodeForTokens(response, onResult)
            }
            exception != null -> {
                Log.e(TAG, "Authorization failed: ${exception.errorDescription}", exception)
                val errorDesc = exception.errorDescription
                when (exception.type) {
                    AuthorizationException.TYPE_GENERAL_ERROR -> {
                        if (errorDesc?.contains("cancelled", ignoreCase = true) == true) {
                            onResult(AuthResult.Cancelled(errorDesc))
                        } else {
                            onResult(AuthResult.Error(
                                errorDesc ?: "Authorization failed",
                                exception
                            ))
                        }
                    }
                    else -> onResult(AuthResult.Error(
                        errorDesc ?: "Authorization error",
                        exception
                    ))
                }
            }
            else -> {
                Log.e(TAG, "Unknown auth result state")
                onResult(AuthResult.Error("Invalid authorization response"))
            }
        }
    }

    /**
     * Exchanges the authorization code for tokens.
     */
    private fun exchangeCodeForTokens(
        response: AuthorizationResponse,
        onResult: (AuthResult) -> Unit
    ) {
        if (authService == null) {
            authService = AuthorizationService(context)
        }

        val tokenRequest = response.createTokenExchangeRequest()

        authService!!.performTokenRequest(tokenRequest) { tokenResponse, tokenException ->
            when {
                tokenResponse != null -> {
                    Log.i(TAG, "Token exchange successful")
                    handleTokenResponse(tokenResponse, onResult)
                }
                tokenException != null -> {
                    Log.e(TAG, "Token exchange failed: ${tokenException.errorDescription}", tokenException)
                    onResult(AuthResult.Error(
                        tokenException.errorDescription ?: "Token exchange failed",
                        tokenException
                    ))
                }
                else -> {
                    Log.e(TAG, "Token exchange returned null response")
                    onResult(AuthResult.Error("Token exchange failed with no response"))
                }
            }
        }
    }

    /**
     * Handles a successful token response.
     * Fetches user info and stores the tokens.
     */
    private fun handleTokenResponse(
        tokenResponse: TokenResponse,
        onResult: (AuthResult) -> Unit
    ) {
        val accessToken = tokenResponse.accessToken
        val refreshToken = tokenResponse.refreshToken
        val expiresAt = tokenResponse.accessTokenExpirationTime

        Log.d(TAG, "handleTokenResponse: accessToken present=${accessToken != null}, " +
                    "refreshToken present=${refreshToken != null}, expiresAt=$expiresAt")

        if (accessToken == null) {
            Log.e(TAG, "handleTokenResponse: No access token in response")
            onResult(AuthResult.Error("No access token in response"))
            return
        }

        if (expiresAt == null) {
            Log.w(TAG, "No expiration time in token response, using default 1 hour")
        }

        // CRITICAL: Save tokens FIRST before fetching user info
        // This ensures tokens persist even if user info fetch fails
        val expirationTime = expiresAt ?: (System.currentTimeMillis() + 60 * 60 * 1000) // 1 hour default
        huggingFaceTokenManager.saveOAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken ?: "",
            expiresAt = expirationTime,
            username = "" // Will update after fetchUserInfo
        )
        Log.i(TAG, "handleTokenResponse: OAuth tokens saved, fetching user info...")

        // Then fetch user info to get the username
        fetchUserInfo(accessToken) { userInfoResult ->
            when (userInfoResult) {
                is UserInfoResult.Success -> {
                    Log.i(TAG, "handleTokenResponse: User info fetched successfully: ${userInfoResult.username}")
                    // Update with actual username
                    huggingFaceTokenManager.saveUsername(userInfoResult.username)
                    onResult(AuthResult.Success(userInfoResult.username))
                }
                is UserInfoResult.Error -> {
                    Log.e(TAG, "Failed to fetch user info: ${userInfoResult.message}")
                    // Still consider auth successful, just without username
                    onResult(AuthResult.Success("unknown"))
                }
            }
        }
    }

    /**
     * Refreshes an expired access token using the refresh token.
     *
     * @param tokenManager The token manager containing the refresh token
     * @return TokenResult with the new access token or error
     */
    suspend fun refreshAccessToken(tokenManager: HuggingFaceTokenManager): TokenResult = withContext(Dispatchers.IO) {
        val refreshToken = tokenManager.getRefreshToken()
            ?: return@withContext TokenResult.Error("No refresh token available")

        suspendCancellableCoroutine<TokenResult> { continuation ->
            if (authService == null) {
                authService = AuthorizationService(context)
            }

            val tokenRequest = TokenRequest.Builder(
                HuggingFaceOAuthConfig.serviceConfig,
                HuggingFaceOAuthConfig.CLIENT_ID
            )
                .setGrantType("refresh_token")
                .setRefreshToken(refreshToken)
                .build()

            authService!!.performTokenRequest(tokenRequest) { tokenResponse, exception ->
                when {
                    tokenResponse != null -> {
                        val newAccessToken = tokenResponse.accessToken
                        val newExpiresAt = tokenResponse.accessTokenExpirationTime

                        if (newAccessToken != null && newExpiresAt != null) {
                            Log.i(TAG, "Token refresh successful")
                            continuation.resume(TokenResult.Success(
                                accessToken = newAccessToken,
                                refreshToken = tokenResponse.refreshToken,
                                expiresAt = newExpiresAt,
                                username = tokenManager.getUsername() ?: "unknown"
                            ))
                        } else {
                            continuation.resume(TokenResult.Error("Invalid token refresh response"))
                        }
                    }
                    exception != null -> {
                        Log.e(TAG, "Token refresh failed: ${exception.errorDescription}", exception)
                        continuation.resume(TokenResult.Error(
                            exception.errorDescription ?: "Token refresh failed",
                            exception
                        ))
                    }
                    else -> {
                        continuation.resume(TokenResult.Error("Token refresh failed with no response"))
                    }
                }
            }
        }
    }

    /**
     * Fetches user info from HuggingFace API.
     */
    private fun fetchUserInfo(accessToken: String, onResult: (UserInfoResult) -> Unit) {
        // Use the existing HuggingFaceApiClient to validate and get username
        val apiClient = HuggingFaceApiClient()

        // Launch a coroutine to validate the token
        GlobalScope.launch(Dispatchers.IO + CrashReporter.handler) {
            when (val result = apiClient.validateToken(accessToken)) {
                is HuggingFaceApiClient.ValidationResult.Success -> {
                    onResult(UserInfoResult.Success(result.username))
                }
                is HuggingFaceApiClient.ValidationResult.Error -> {
                    onResult(UserInfoResult.Error(result.message))
                }
            }
        }
    }

    /**
     * Releases resources used by the authorization service.
     * Should be called when the manager is no longer needed.
     */
    fun dispose() {
        authService?.dispose()
        authService = null
        Log.d(TAG, "Authorization service disposed")
    }

    /**
     * User info fetch result.
     */
    private sealed class UserInfoResult {
        data class Success(val username: String) : UserInfoResult()
        data class Error(val message: String) : UserInfoResult()
    }
}
