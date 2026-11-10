package com.antivocale.app.data.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-969: the one-taxonomy-at-the-source contract. The connect and body
 * phases of [ResumeDownloadHelper.downloadWithResume] must box their raw
 * IOExceptions (UnknownHost, SocketTimeout, reset: the three incident
 * shapes behind the stalled-import task) as
 * [DownloadException.NetworkError], so every retry layer above sees ONE
 * failure shape instead of two. The .invalid TLD is guaranteed
 * unresolvable (RFC 6761): resolution fails before any socket opens, on
 * every network the suite runs on.
 */
class ResumeDownloadHelperNetworkErrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an unresolvable host comes back boxed as NetworkError, not raw IOException`() {
        val target = File(tmp.root, "f.onnx")
        val result = ResumeDownloadHelper.downloadWithResume(
            DownloadConfig(
                url = "http://host-that-cannot-resolve.invalid/f.onnx",
                tempFile = target,
                targetFile = target,
                estimatedSizeBytes = 1L))
        assertTrue(result.isFailure)
        assertTrue(
            "expected NetworkError, got ${result.exceptionOrNull()?.javaClass}: " +
                result.exceptionOrNull()?.message,
            result.exceptionOrNull() is DownloadException.NetworkError)
    }

    @Test
    fun `a stall before the status line boxes as NetworkError`() {
        // The review pass found the status-line read outside the first
        // boxing pass: a server that ACCEPTS the connection and then never
        // answers is the mid-decode stall shape, and it throws from
        // responseCode, not connect. It must ride the ladder like every
        // other connection failure.
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { /* never respond */ }
        server.start()
        try {
            val target = File(tmp.root, "f.onnx")
            val result = ResumeDownloadHelper.downloadWithResume(
                DownloadConfig(
                    url = "http://127.0.0.1:${server.address.port}/f.onnx",
                    tempFile = target,
                    targetFile = target,
                    estimatedSizeBytes = 1L,
                    readTimeoutMs = 300))
            assertTrue(result.isFailure)
            assertTrue(
                "expected NetworkError, got ${result.exceptionOrNull()?.javaClass}: " +
                    result.exceptionOrNull()?.message,
                result.exceptionOrNull() is DownloadException.NetworkError)
        } finally {
            server.stop(0)
        }
    }
}
