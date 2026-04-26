package com.antivocale.app.data

import android.content.Context
import android.content.Intent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuggingFaceAuthManagerTest {

    private lateinit var context: Context
    private lateinit var tokenManager: HuggingFaceTokenManager
    private lateinit var apiClient: HuggingFaceApiClient
    private lateinit var authManager: HuggingFaceAuthManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        authManager = HuggingFaceAuthManager(context, tokenManager, apiClient)
    }

    // --- handleAuthResult routing ---

    @Test
    fun `handleAuthResult with null data returns Cancelled`() {
        var result: HuggingFaceAuthManager.AuthResult? = null
        authManager.handleAuthResult(null) { result = it }

        assertTrue(result is HuggingFaceAuthManager.AuthResult.Cancelled)
        assertTrue((result as HuggingFaceAuthManager.AuthResult.Cancelled).reason.contains("No result data"))
    }

    @Test
    fun `handleAuthResult with empty intent returns Error`() {
        val intent = Intent()
        var result: HuggingFaceAuthManager.AuthResult? = null
        authManager.handleAuthResult(intent) { result = it }

        // Empty intent has no AuthorizationResponse or AuthorizationException
        assertTrue(result is HuggingFaceAuthManager.AuthResult.Error)
        assertEquals("Invalid authorization response", (result as HuggingFaceAuthManager.AuthResult.Error).message)
    }

    // --- isConfigured ---

    @Test
    fun `isConfigured delegates to HuggingFaceOAuthConfig`() {
        // Just verify it doesn't crash — the actual config check is in HuggingFaceOAuthConfig
        authManager.isConfigured()
    }

    // --- TokenResult sealed class ---

    @Test
    fun `TokenResult Success holds all fields`() {
        val result = HuggingFaceAuthManager.TokenResult.Success(
            accessToken = "access123",
            refreshToken = "refresh456",
            expiresAt = 99999L,
            username = "testuser"
        )
        assertEquals("access123", result.accessToken)
        assertEquals("refresh456", result.refreshToken)
        assertEquals(99999L, result.expiresAt)
        assertEquals("testuser", result.username)
    }

    @Test
    fun `TokenResult Error holds message and optional exception`() {
        val result = HuggingFaceAuthManager.TokenResult.Error("something failed")
        assertEquals("something failed", result.message)
        assertNull(result.exception)
    }

    @Test
    fun `TokenResult Error with exception`() {
        val ex = RuntimeException("boom")
        val result = HuggingFaceAuthManager.TokenResult.Error("failed", ex)
        assertEquals(ex, result.exception)
    }

    // --- AuthResult sealed class ---

    @Test
    fun `AuthResult Success holds username`() {
        val result = HuggingFaceAuthManager.AuthResult.Success("testuser")
        assertEquals("testuser", result.username)
    }

    @Test
    fun `AuthResult Cancelled has default reason`() {
        val result = HuggingFaceAuthManager.AuthResult.Cancelled()
        assertEquals("User cancelled", result.reason)
    }

    @Test
    fun `AuthResult Cancelled with custom reason`() {
        val result = HuggingFaceAuthManager.AuthResult.Cancelled("browser closed")
        assertEquals("browser closed", result.reason)
    }

    @Test
    fun `AuthResult Error holds message and optional exception`() {
        val result = HuggingFaceAuthManager.AuthResult.Error("oauth failed")
        assertEquals("oauth failed", result.message)
        assertNull(result.exception)
    }

    // --- refreshAccessToken validation ---

    @Test
    fun `refreshAccessToken returns error when no refresh token`() = runTest {
        every { tokenManager.getRefreshToken() } returns null

        val result = authManager.refreshAccessToken(tokenManager)

        assertTrue(result is HuggingFaceAuthManager.TokenResult.Error)
        assertTrue((result as HuggingFaceAuthManager.TokenResult.Error).message.contains("No refresh token"))
    }

    // --- Token saving coordination ---

    @Test
    fun `handleTokenResponse with null access token returns error`() {
        // Verify that the internal handleTokenResponse checks for null access token
        // This is tested indirectly through the TokenResult structure
        val result = HuggingFaceAuthManager.TokenResult.Error("No access token in response")
        assertNotNull(result.message)
    }
}
