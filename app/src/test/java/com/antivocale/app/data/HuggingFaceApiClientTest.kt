package com.antivocale.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HuggingFaceApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: HuggingFaceApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val mockBaseUrl: HttpUrl = server.url("/")
        apiClient = HuggingFaceApiClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val redirected = original.newBuilder()
                        .url(
                            mockBaseUrl.newBuilder()
                                .encodedPath(original.url.encodedPath)
                                .encodedQuery(original.url.encodedQuery)
                                .build()
                        )
                        .build()
                    chain.proceed(redirected)
                }
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Empty token ---

    @Test
    fun `empty token returns error without network call`() = runTest {
        val result = apiClient.validateToken("")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertEquals("Token cannot be empty", (result as HuggingFaceApiClient.ValidationResult.Error).message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank token returns error without network call`() = runTest {
        val result = apiClient.validateToken("   ")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertEquals(0, server.requestCount)
    }

    // --- Valid token ---

    @Test
    fun `valid token returns success with username`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"id": "testuser", "name": "Test User"}""")
            .setResponseCode(200))

        val result = apiClient.validateToken("hf_validtoken")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Success)
        assertEquals("testuser", (result as HuggingFaceApiClient.ValidationResult.Success).username)

        val request = server.takeRequest()
        assertEquals("Bearer hf_validtoken", request.getHeader("Authorization"))
    }

    // --- HTTP errors ---

    @Test
    fun `HTTP 401 returns invalid token error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.validateToken("hf_badtoken")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertTrue((result as HuggingFaceApiClient.ValidationResult.Error).message.contains("Invalid token"))
    }

    @Test
    fun `HTTP 403 returns permission error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = apiClient.validateToken("hf_limitedtoken")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertTrue((result as HuggingFaceApiClient.ValidationResult.Error).message.contains("permissions"))
    }

    @Test
    fun `HTTP 500 returns server error`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"error": "Internal server error"}""")
            .setResponseCode(500))

        val result = apiClient.validateToken("hf_token")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        val message = (result as HuggingFaceApiClient.ValidationResult.Error).message
        assertTrue(message.contains("Internal server error"))
    }

    // --- Edge cases ---

    @Test
    fun `200 with empty body returns error`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(""))

        val result = apiClient.validateToken("hf_token")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        val message = (result as HuggingFaceApiClient.ValidationResult.Error).message
        assertTrue(message.contains("Empty response") || message.contains("Could not find"))
    }

    @Test
    fun `200 with malformed JSON returns parse error`() = runTest {
        server.enqueue(MockResponse()
            .setBody("not json at all")
            .setResponseCode(200))

        val result = apiClient.validateToken("hf_token")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertTrue((result as HuggingFaceApiClient.ValidationResult.Error).message.contains("parse"))
    }

    @Test
    fun `200 with missing id field returns parse error`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"name": "Test User"}""")
            .setResponseCode(200))

        val result = apiClient.validateToken("hf_token")

        assertTrue(result is HuggingFaceApiClient.ValidationResult.Error)
        assertTrue((result as HuggingFaceApiClient.ValidationResult.Error).message.contains("parse"))
    }
}
