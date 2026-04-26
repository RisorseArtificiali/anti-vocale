package com.antivocale.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HuggingFaceTokenManagerTest {

    // Use a minimal fake to access maskToken and interface logic
    private val manager = object : HuggingFaceTokenManager {
        private var token: String? = null
        private var accessToken: String? = null
        private var refreshToken: String? = null
        private var expiresAt: Long? = null
        private var username: String? = null
        private var authType: HuggingFaceTokenManager.AuthType = HuggingFaceTokenManager.AuthType.MANUAL
        private val _tokenState = MutableStateFlow<HuggingFaceTokenManager.TokenState>(
            HuggingFaceTokenManager.TokenState.Idle
        )
        override val tokenState = _tokenState.asStateFlow()

        override fun saveToken(token: String) { this.token = token }
        override fun getToken(): String? = token
        override fun hasToken(): Boolean = when (authType) {
            HuggingFaceTokenManager.AuthType.OAUTH -> !accessToken.isNullOrEmpty()
            HuggingFaceTokenManager.AuthType.MANUAL -> !token.isNullOrEmpty()
        }
        override fun clearToken() {
            token = null; username = null; accessToken = null; refreshToken = null; expiresAt = null
            authType = HuggingFaceTokenManager.AuthType.MANUAL
            _tokenState.value = HuggingFaceTokenManager.TokenState.Idle
        }
        override fun saveUsername(username: String) { this.username = username }
        override fun getUsername(): String? = username
        override fun setTokenState(state: HuggingFaceTokenManager.TokenState) { _tokenState.value = state }
        override fun getCurrentTokenState(): HuggingFaceTokenManager.TokenState = _tokenState.value
        override fun maskToken(token: String): String = when {
            token.isEmpty() -> ""
            token.length <= 8 -> "${token.take(4)}...${token.takeLast(2)}"
            token.startsWith("hf_") -> {
                val after = token.substring(3)
                if (after.length <= 4) "hf_${after}..." else "hf_${after.take(3)}...${after.takeLast(4)}"
            }
            else -> "${token.take(4)}...${token.takeLast(4)}"
        }
        override fun saveOAuthTokens(accessToken: String, refreshToken: String, expiresAt: Long, username: String) {
            this.accessToken = accessToken; this.refreshToken = refreshToken; this.expiresAt = expiresAt
            this.username = username; this.authType = HuggingFaceTokenManager.AuthType.OAUTH
            _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(
                username, maskToken(accessToken), HuggingFaceTokenManager.AuthType.OAUTH, expiresAt
            )
        }
        override fun getOAuthAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = refreshToken
        override fun getTokenExpiration(): Long? = expiresAt
        override fun getAuthType(): HuggingFaceTokenManager.AuthType = authType
        override fun isOAuthToken(): Boolean = authType == HuggingFaceTokenManager.AuthType.OAUTH
        override fun needsTokenRefresh(): Boolean {
            val s = _tokenState.value
            return s is HuggingFaceTokenManager.TokenState.Valid && s.authType == HuggingFaceTokenManager.AuthType.OAUTH && s.needsRefresh()
        }
        override fun isTokenExpired(): Boolean {
            val s = _tokenState.value
            return s is HuggingFaceTokenManager.TokenState.Valid && s.authType == HuggingFaceTokenManager.AuthType.OAUTH && s.isExpired()
        }
        override fun updateAccessToken(accessToken: String, expiresAt: Long) {
            this.accessToken = accessToken; this.expiresAt = expiresAt
            _tokenState.value = HuggingFaceTokenManager.TokenState.Valid(
                username ?: return, maskToken(accessToken), HuggingFaceTokenManager.AuthType.OAUTH, expiresAt
            )
        }
        override fun getEffectiveToken(): String? = when (authType) {
            HuggingFaceTokenManager.AuthType.OAUTH -> accessToken
            HuggingFaceTokenManager.AuthType.MANUAL -> token
        }
    }

    // --- maskToken ---

    @Test
    fun `maskToken with hf_ prefix and long body`() {
        // "hf_Abcdefghijklmnopqwxyz" length=24, >8, startsWith("hf_") → after="Abcdefghijklmnopqwxyz"
        // take(3)="Abc", takeLast(4)="wxyz"
        assertEquals("hf_Abc...wxyz", manager.maskToken("hf_Abcdefghijklmnopqwxyz"))
    }

    @Test
    fun `maskToken with hf_ prefix and short body`() {
        // "hf_abcd" length=7, <=8 branch fires first: take(4)...takeLast(2)
        assertEquals("hf_a...cd", manager.maskToken("hf_abcd"))
    }

    @Test
    fun `maskToken with hf_ prefix and medium body`() {
        // "hf_allen" length=8, exactly <=8: take(4)...takeLast(2)
        assertEquals("hf_a...en", manager.maskToken("hf_allen"))
    }

    @Test
    fun `maskToken with long non-hf token`() {
        assertEquals("abcd...wxyz", manager.maskToken("abcdefghijklmnopqrstwxyz"))
    }

    @Test
    fun `maskToken with empty string`() {
        assertEquals("", manager.maskToken(""))
    }

    @Test
    fun `maskToken with exactly 8 chars`() {
        val token = "12345678"
        assertEquals("1234...78", manager.maskToken(token))
    }

    // --- Token state flow ---

    @Test
    fun `token state starts as Idle`() {
        assertTrue(manager.getCurrentTokenState() is HuggingFaceTokenManager.TokenState.Idle)
    }

    @Test
    fun `setTokenState updates state`() {
        manager.setTokenState(HuggingFaceTokenManager.TokenState.Validating)
        assertTrue(manager.getCurrentTokenState() is HuggingFaceTokenManager.TokenState.Validating)
    }

    @Test
    fun `clearToken resets state to Idle`() {
        manager.saveToken("hf_test")
        manager.saveUsername("user")
        manager.setTokenState(HuggingFaceTokenManager.TokenState.Valid("user", "hf_..."))
        manager.clearToken()
        assertTrue(manager.getCurrentTokenState() is HuggingFaceTokenManager.TokenState.Idle)
        assertNull(manager.getToken())
        assertNull(manager.getUsername())
    }

    // --- Manual token ---

    @Test
    fun `save and retrieve manual token`() {
        manager.saveToken("hf_abc123xyz")
        assertEquals("hf_abc123xyz", manager.getToken())
    }

    @Test
    fun `hasToken returns false when no token`() {
        assertFalse(manager.hasToken())
    }

    @Test
    fun `hasToken returns true after saving token`() {
        manager.saveToken("hf_test123")
        assertTrue(manager.hasToken())
    }

    @Test
    fun `getEffectiveToken returns manual token`() {
        manager.saveToken("hf_manual_token")
        assertEquals("hf_manual_token", manager.getEffectiveToken())
    }

    // --- OAuth tokens ---

    @Test
    fun `saveOAuthTokens stores all fields`() {
        val expiresAt = System.currentTimeMillis() + 3600000
        manager.saveOAuthTokens("access123", "refresh456", expiresAt, "oauthuser")

        assertEquals("access123", manager.getOAuthAccessToken())
        assertEquals("refresh456", manager.getRefreshToken())
        assertEquals(expiresAt, manager.getTokenExpiration())
        assertEquals("oauthuser", manager.getUsername())
        assertTrue(manager.isOAuthToken())
        assertEquals(HuggingFaceTokenManager.AuthType.OAUTH, manager.getAuthType())
    }

    @Test
    fun `hasToken returns true for OAuth token`() {
        manager.saveOAuthTokens("access", "refresh", System.currentTimeMillis() + 3600000, "user")
        assertTrue(manager.hasToken())
    }

    @Test
    fun `getEffectiveToken returns OAuth access token`() {
        manager.saveOAuthTokens("oauth_access", "refresh", System.currentTimeMillis() + 3600000, "user")
        assertEquals("oauth_access", manager.getEffectiveToken())
    }

    @Test
    fun `saveOAuthTokens sets Valid state with OAuth authType`() {
        val expiresAt = System.currentTimeMillis() + 3600000
        manager.saveOAuthTokens("access", "refresh", expiresAt, "testuser")

        val state = manager.getCurrentTokenState()
        assertTrue(state is HuggingFaceTokenManager.TokenState.Valid)
        state as HuggingFaceTokenManager.TokenState.Valid
        assertEquals("testuser", state.username)
        assertEquals(HuggingFaceTokenManager.AuthType.OAUTH, state.authType)
        assertEquals(expiresAt, state.expiresAt)
    }

    // --- Token expiration ---

    @Test
    fun `needsTokenRefresh returns false for non-expired token`() {
        manager.saveOAuthTokens("access", "refresh", System.currentTimeMillis() + 600000, "user")
        assertFalse(manager.needsTokenRefresh())
    }

    @Test
    fun `needsTokenRefresh returns true for nearly expired token`() {
        val expiresAt = System.currentTimeMillis() + 60000 // within 5-min buffer
        manager.saveOAuthTokens("access", "refresh", expiresAt, "user")
        assertTrue(manager.needsTokenRefresh())
    }

    @Test
    fun `isTokenExpired returns true for expired token`() {
        val expiresAt = System.currentTimeMillis() - 1000
        manager.saveOAuthTokens("access", "refresh", expiresAt, "user")
        assertTrue(manager.isTokenExpired())
    }

    @Test
    fun `updateAccessToken updates token and expiration`() {
        manager.saveOAuthTokens("old_access", "refresh", 1000L, "user")
        val newExpires = System.currentTimeMillis() + 7200000
        manager.updateAccessToken("new_access", newExpires)

        assertEquals("new_access", manager.getOAuthAccessToken())
        assertEquals(newExpires, manager.getTokenExpiration())
    }

    @Test
    fun `clearToken removes OAuth data`() {
        manager.saveOAuthTokens("access", "refresh", System.currentTimeMillis() + 3600000, "user")
        manager.clearToken()

        assertNull(manager.getOAuthAccessToken())
        assertNull(manager.getRefreshToken())
        assertNull(manager.getTokenExpiration())
        assertFalse(manager.isOAuthToken())
    }

    // --- TokenState.Valid logic ---

    @Test
    fun `TokenState Valid needsRefresh with expiration in buffer`() {
        val state = HuggingFaceTokenManager.TokenState.Valid(
            "user", "masked", HuggingFaceTokenManager.AuthType.OAUTH,
            System.currentTimeMillis() + 100000 // within 5-min buffer
        )
        assertTrue(state.needsRefresh())
    }

    @Test
    fun `TokenState Valid needsRefresh returns false for manual tokens`() {
        val state = HuggingFaceTokenManager.TokenState.Valid("user", "masked")
        assertFalse(state.needsRefresh())
    }

    @Test
    fun `TokenState Valid isExpired with past expiration`() {
        val state = HuggingFaceTokenManager.TokenState.Valid(
            "user", "masked", HuggingFaceTokenManager.AuthType.OAUTH,
            System.currentTimeMillis() - 1000
        )
        assertTrue(state.isExpired())
    }

    @Test
    fun `TokenState Valid isExpired returns false for future expiration`() {
        val state = HuggingFaceTokenManager.TokenState.Valid(
            "user", "masked", HuggingFaceTokenManager.AuthType.OAUTH,
            System.currentTimeMillis() + 3600000
        )
        assertFalse(state.isExpired())
    }
}
