package com.antivocale.app.transcription

import android.content.Context
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TASK-681: the LAN-offload backend against a MockWebServer OmniVoice
 * double. runBlocking (not runTest) on purpose: the wall-clock budget is a
 * REAL timeout and runTest's virtual clock would trip withTimeout while the
 * round trip is still in flight.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class RemoteOmnivoiceBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var backend: RemoteOmnivoiceBackend

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        backend = RemoteOmnivoiceBackend(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun initialize(baseUrl: String, model: String = "whisper-1") = runBlocking {
        backend.initialize(
            mockk<Context>(),
            BackendConfig.RemoteConfig(
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = model,
            ))
    }

    private fun audioFile(name: String = "clip.m4a"): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(2048) { it.toByte() }) }

    private fun RecordedRequest.bodyText(): String {
        val buffer = body ?: Buffer()
        return buffer.readUtf8()
    }

    @Test
    fun `transcribeFile posts the original file to the transcriptions endpoint`() {
        server.enqueue(MockResponse().setBody("""{"text": "hello from the box"}"""))
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile("voice.ogg")

        val result = runBlocking { backend.transcribeFile(file.path, language = "it") }

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("hello from the box", result.getOrNull()!!.text)
        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type") ?: ""
        assertTrue("expected multipart body, got $contentType", contentType.startsWith("multipart/"))
        val body = request.bodyText()
        assertTrue("model field missing", body.contains("name=\"model\""))
        assertTrue("model value missing", body.contains("whisper-1"))
        assertTrue("response_format field missing", body.contains("name=\"response_format\""))
        assertTrue("json format missing", body.contains("json"))
        assertTrue("language pin missing", body.contains("name=\"language\""))
        assertTrue("file part missing", body.contains("name=\"file\""))
        assertTrue("file name missing", body.contains("voice.ogg"))
    }

    @Test
    fun `no language pin omits the language field`() {
        server.enqueue(MockResponse().setBody("""{"text": "ok"}"""))
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile()

        val result = runBlocking { backend.transcribeFile(file.path, language = "") }

        assertTrue(result.isSuccess)
        assertFalse("language field must be absent without a pin",
            server.takeRequest().bodyText().contains("name=\"language\""))
    }

    @Test
    fun `an HTTP error maps to RemoteServerError with the FastAPI detail`() {
        server.enqueue(MockResponse().setResponseCode(500)
            .setBody("""{"detail": {"queue_position": 2, "deadline_s": 90}}"""))
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile()

        val result = runBlocking { backend.transcribeFile(file.path, language = "") }

        val error = result.exceptionOrNull()
        assertTrue("expected RemoteServerError, got $error",
            error is TranscriptionException.RemoteServerError)
        error as TranscriptionException.RemoteServerError
        assertEquals(500, error.statusCode)
        assertTrue("queue/deadline detail must ride the error: ${error.serverDetail}",
            error.serverDetail.contains("queue_position") && error.serverDetail.contains("90"))
    }

    @Test
    fun `an unreachable server maps to RemoteUnreachableException`() {
        // A shut-down MockWebServer keeps a port with nothing listening: the
        // exact "box off / wrong address" LAN condition.
        server.shutdown()
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile()

        val result = runBlocking { backend.transcribeFile(file.path, language = "") }

        assertTrue("expected RemoteUnreachableException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is TranscriptionException.RemoteUnreachableException)
    }

    @Test
    fun `the wall-clock budget trip maps to RemoteTimeoutException`() {
        backend.wallClockBudgetMs = 200L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(2_000)
                return MockResponse().setBody("""{"text": "late"}""")
            }
        }
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile()

        val result = runBlocking { backend.transcribeFile(file.path, language = "") }

        val error = result.exceptionOrNull()
        assertTrue("expected RemoteTimeoutException, got $error",
            error is TranscriptionException.RemoteTimeoutException)
    }

    @Test
    fun `a blank transcript maps to NoTranscriptionProduced`() {
        server.enqueue(MockResponse().setBody("""{"text": ""}"""))
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val file = audioFile()

        val result = runBlocking { backend.transcribeFile(file.path, language = "") }

        assertTrue(result.exceptionOrNull() is TranscriptionException.NoTranscriptionProduced)
    }

    @Test
    fun `without initialize the request fails NotInitialized`() {
        val file = audioFile()
        val result = runBlocking { backend.transcribeFile(file.path, language = "") }
        assertTrue(result.exceptionOrNull() is TranscriptionException.NotInitialized)
    }

    @Test
    fun `a missing file fails with ModelLoadError`() {
        assertTrue(initialize(server.url("/").toString()).isSuccess)
        val result = runBlocking {
            backend.transcribeFile(tmp.root.resolve("gone.wav").path, language = "")
        }
        assertTrue(result.exceptionOrNull() is TranscriptionException.ModelLoadError)
    }

    @Test
    fun `transcribeAudio wraps samples as a wav container`() {
        server.enqueue(MockResponse().setBody("""{"text": "samples text"}"""))
        assertTrue(initialize(server.url("/").toString()).isSuccess)

        val result = runBlocking {
            backend.transcribeAudio(FloatArray(1600), 16000, prompt = "")
        }

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        val body = request.bodyText()
        assertTrue("wav file part missing", body.contains("name=\"file\""))
        assertTrue("wav file name missing", body.contains("audio.wav"))
    }

    @Test
    fun `testConnection proves address, auth and decode in one round trip`() {
        server.enqueue(MockResponse().setBody("""{"text": ""}"""))
        val verdict = runBlocking {
            backend.testConnection(server.url("/").toString(), "key", "whisper-1")
        }
        assertEquals(RemoteOmnivoiceBackend.ConnectionTestResult.Success, verdict)
        assertEquals("/v1/audio/transcriptions", server.takeRequest().path)
    }

    @Test
    fun `testConnection accepts a bare host and port`() {
        server.enqueue(MockResponse().setBody("""{"text": ""}"""))
        // Strip the scheme: the user may type 192.168.1.10:3900 bare.
        val bare = server.url("/").toString().removePrefix("http://")
        val verdict = runBlocking { backend.testConnection(bare, "key", "whisper-1") }
        assertEquals(RemoteOmnivoiceBackend.ConnectionTestResult.Success, verdict)
    }

    @Test
    fun `testConnection reports an auth rejection typed`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail": "bad key"}"""))
        val verdict = runBlocking {
            backend.testConnection(server.url("/").toString(), "wrong", "whisper-1")
        }
        assertEquals(RemoteOmnivoiceBackend.ConnectionTestResult.AuthRejected, verdict)
    }

    @Test
    fun `testConnection reports a server error with the status code`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail": "boom"}"""))
        val verdict = runBlocking {
            backend.testConnection(server.url("/").toString(), "key", "whisper-1")
        }
        assertEquals(RemoteOmnivoiceBackend.ConnectionTestResult.ServerError(500), verdict)
    }

    @Test
    fun `testConnection on a blank address is unreachable without any network call`() {
        val verdict = runBlocking { backend.testConnection("   ", "key", "whisper-1") }
        assertEquals(RemoteOmnivoiceBackend.ConnectionTestResult.Unreachable, verdict)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the endpoint is the backend path identity`() {
        assertTrue(initialize("http://192.168.1.10:3900").isSuccess)
        assertEquals("http://192.168.1.10:3900", backend.getModelPath())
        assertTrue(backend.isReady())
        backend.unload()
        assertFalse(backend.isReady())
    }

    @Test
    fun `a wrong config type fails initialization`() {
        val result = runBlocking {
            backend.initialize(mockk(), BackendConfig.LiteRTConfig("/x"))
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
