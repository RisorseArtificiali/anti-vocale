package com.localai.bridge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API client for HuggingFace services.
 *
 * Handles token validation and communication with HuggingFace's API.
 */
class HuggingFaceApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val WHOAMI_URL = "https://huggingface.co/api/whoami-v2"
    }

    /**
     * Result of token validation.
     */
    sealed class ValidationResult {
        data class Success(val username: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    /**
     * Validates a HuggingFace token by calling the whoami-v2 API.
     *
     * @param token The HuggingFace token to validate.
     * @return ValidationResult.Success with username if valid, Error with message if invalid.
     */
    suspend fun validateToken(token: String): ValidationResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext ValidationResult.Error("Token cannot be empty")
        }

        val request = Request.Builder()
            .url(WHOAMI_URL)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                        if (body != null) {
                            try {
                                // Parse JSON response to extract username
                                // Response format: {"type":"user","id":"username",...}
                                val username = parseUsernameFromJson(body)
                                ValidationResult.Success(username)
                            } catch (e: Exception) {
                                ValidationResult.Error("Failed to parse response: ${e.message}")
                            }
                        } else {
                            ValidationResult.Error("Empty response from server")
                        }
                    }
                    401 -> ValidationResult.Error("Invalid token. Please check your HuggingFace token.")
                    403 -> ValidationResult.Error("Token does not have required permissions.")
                    else -> {
                        val errorBody = response.body?.string()
                        val errorMsg = if (!errorBody.isNullOrBlank()) {
                            // Try to extract error message from JSON
                            try {
                                parseErrorMessageFromJson(errorBody)
                            } catch (e: Exception) {
                                "HTTP ${response.code}"
                            }
                        } else {
                            "HTTP ${response.code}"
                        }
                        ValidationResult.Error("Validation failed: $errorMsg")
                    }
                }
            }
        } catch (e: IOException) {
            ValidationResult.Error("Network error: ${e.message ?: "Check your internet connection"}")
        } catch (e: Exception) {
            ValidationResult.Error("Unexpected error: ${e.message}")
        }
    }

    /**
     * Parses the username from the whoami-v2 API response.
     *
     * Expected format: {"type":"user","id":"username",...}
     */
    private fun parseUsernameFromJson(json: String): String {
        // Simple JSON parsing to avoid additional dependencies
        // Find "id":"username" pattern
        val idPattern = """"id"\s*:\s*"([^"]+)"""".toRegex()
        val match = idPattern.find(json)
        return match?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Could not find username in response")
    }

    /**
     * Parses an error message from a JSON error response.
     */
    private fun parseErrorMessageFromJson(json: String): String {
        // Try to find error message in common formats
        val errorPattern = """"error"\s*:\s*"([^"]+)"""".toRegex()
        val match = errorPattern.find(json)
        return match?.groupValues?.get(1) ?: "Unknown error"
    }
}
