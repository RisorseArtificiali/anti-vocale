package com.antivocale.app.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * TEST-ONLY localhost HTTP server (JDK built-in; no Android, no Robolectric):
 * serves schema-1 manifest documents and their model files so the REAL
 * importer path (fetch via [HuggingFaceRepoListing.fetchText], resumable
 * download via ResumeDownloadHelper) runs end-to-end in a unit test with zero
 * production seams changed. Byte fixtures use the same ONNX-magic shapes
 * [ExternalModelImporterTest]'s local imports use, so the download core's
 * hash verification and the registration-time integrity gate both pass.
 */
class LocalModelServer : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val documents = HashMap<String, ByteArray>()

    /** TASK-969: remaining forced failures per path (see [failNext]). */
    private val failureBudget = HashMap<String, Int>()
    private val failureStatus = HashMap<String, Int>()

    /** TASK-969: requests received per path (forced failures included), so
     *  tests can pin HOW MANY attempts a retry policy actually made. */
    private val requestCounts = HashMap<String, Int>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            requestCounts[path] = (requestCounts[path] ?: 0) + 1
            val budget = failureBudget[path]
            if (budget != null && budget > 0) {
                failureBudget[path] = budget - 1
                exchange.sendResponseHeaders(failureStatus[path] ?: 503, -1)
                exchange.close()
                return@createContext
            }
            val bytes = documents[path]
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    /** Requests received for [path], forced failures included. */
    fun requestsServed(path: String): Int = requestCounts[path] ?: 0

    /**
     * TASK-969: makes an already-served [path] answer [status] for its next
     * [failures] requests, then serve the registered bytes normally again
     * (the bytes stay pinned, so a retried download still verifies).
     */
    fun failNext(path: String, failures: Int, status: Int = 503) {
        failureBudget[path] = failures
        failureStatus[path] = status
    }

    /** Registers a text document; returns its full url. */
    fun serve(path: String, text: String): String {
        documents[path] = text.toByteArray()
        return baseUrl + path
    }

    /** Registers a binary document; returns its full url. */
    fun serve(path: String, bytes: ByteArray): String {
        documents[path] = bytes
        return baseUrl + path
    }

    /** A synthetic valid-magic "ONNX" payload (first byte 0x08, like the local-import fixtures). */
    fun onnxFixture(size: Int, filler: Byte): ByteArray = ByteArray(size) { if (it == 0) 8 else filler }

    /**
     * Serves the four canonical transducer files plus a schema-1 manifest
     * naming them (sha256/size computed from the served bytes), and returns
     * the manifest url. [transform] edits the manifest JSON after the files
     * block is assembled (inject extra top-level fields).
     */
    fun serveSchema1Manifest(
        model: String,
        extraTopLevel: String = "",
        extraFiles: String = "",
    ): String {
        // All three ONNX fixtures clear the registration integrity gate's
        // suspiciously-small floor (a 512B joiner reads as a truncated
        // download), with the same first-byte magic as the local fixtures.
        val encoder = onnxFixture(4096, 1)
        val decoder = onnxFixture(2048, 2)
        val joiner = onnxFixture(2048, 3)
        val tokens = (0..99).joinToString("\n") { "tok$it $it" }
        serve("encoder.int8.onnx", encoder)
        serve("decoder.int8.onnx", decoder)
        serve("joiner.int8.onnx", joiner)
        serve("tokens.txt", tokens.toByteArray())
        val manifest = """
            {"schema":1,"model":"$model","licence":"CC-BY-NC-4.0",
             "base_url":"$baseUrl",
             "files":{
               "encoder.int8.onnx":${fileEntry(encoder)},
               "decoder.int8.onnx":${fileEntry(decoder)},
               "joiner.int8.onnx":${fileEntry(joiner)},
               "tokens.txt":${fileEntry(tokens.toByteArray())}$extraFiles
             }$extraTopLevel}
        """.trimIndent()
        return serve("manifest.json", manifest)
    }

    private fun fileEntry(bytes: ByteArray): String =
        "{\"size\":${bytes.size},\"sha256\":\"${sha256(bytes)}\"}"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    override fun close() {
        server.stop(0)
    }

    companion object {
        /** The four canonical names a transducer set lands under. */
        val CANONICAL_FILES = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    }
}
