package com.antivocale.app.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Singleton
class HuggingFaceApiClient @Inject constructor(
    private val client: OkHttpClient
) {

    companion object {
        private const val WHOAMI_URL = "https://huggingface.co/api/whoami-v2"
    }

    sealed class ValidationResult {
        data class Success(val username: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

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

    private fun parseUsernameFromJson(json: String): String {
        val idPattern = """"id"\s*:\s*"([^"]+)"""".toRegex()
        val match = idPattern.find(json)
        return match?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Could not find username in response")
    }

    private fun parseErrorMessageFromJson(json: String): String {
        val errorPattern = """"error"\s*:\s*"([^"]+)"""".toRegex()
        val match = errorPattern.find(json)
        return match?.groupValues?.get(1) ?: "Unknown error"
    }
}
