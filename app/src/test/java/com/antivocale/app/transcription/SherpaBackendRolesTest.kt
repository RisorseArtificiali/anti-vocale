package com.antivocale.app.transcription

import com.antivocale.app.testing.RepoFiles
import com.antivocale.app.data.catalog.CatalogFile
import com.antivocale.app.data.catalog.CatalogSource
import com.antivocale.app.data.catalog.CatalogVariant
import com.antivocale.app.data.catalog.ModelCatalogJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [SherpaBackend.resolveRoles] against the real export naming schemes carried
 * by the bundled catalog. Role resolution is name-driven (contains-matching, never
 * list position), so a GigaAM `gigaam_v3_e2e_rnnt_encoder_int8.onnx`, a Whisper
 * `turbo-encoder.int8.onnx`, or a Qwen3 `tokenizer/...` subdir must resolve to the
 * same roles as the canonical `encoder.int8.onnx` names.
 */
class SherpaBackendRolesTest {

    private fun variant(name: String, vararg fileNames: String) = CatalogVariant(
        name = name,
        dirName = "test-$name",
        estimatedSizeMB = 0,
        source = CatalogSource(kind = "url", template = "https://example.test/{file}"),
        files = fileNames.map { CatalogFile(name = it) },
    )

    // ---- Real bundled-catalog naming schemes ----

    @Test
    fun `parakeet canonical names resolve all four transducer roles`() {
        val roles = SherpaBackend.resolveRoles(
            variant("int8", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))
        assertEquals("encoder.int8.onnx", roles.encoder)
        assertEquals("decoder.int8.onnx", roles.decoder)
        assertEquals("joiner.int8.onnx", roles.joiner)
        assertEquals("tokens.txt", roles.tokens)
        assertNull(roles.convFrontend)
        assertNull(roles.tokenizerDir)
    }

    @Test
    fun `gigaam rnnt-prefixed names resolve with joint marker`() {
        val roles = SherpaBackend.resolveRoles(variant(
            "gigaam-v3",
            "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
            "gigaam_v3_e2e_rnnt_decoder.onnx",
            "gigaam_v3_e2e_rnnt_joint.onnx",
            "gigaam_v3_e2e_rnnt_tokens.txt",
        ))
        assertEquals("gigaam_v3_e2e_rnnt_encoder_int8.onnx", roles.encoder)
        assertEquals("gigaam_v3_e2e_rnnt_decoder.onnx", roles.decoder)
        // GigaAM exports the joiner as *joint*: resolveRoles must map it to the joiner role.
        assertEquals("gigaam_v3_e2e_rnnt_joint.onnx", roles.joiner)
        assertEquals("gigaam_v3_e2e_rnnt_tokens.txt", roles.tokens)
    }

    @Test
    fun `whisper turbo prefixed names resolve encoder decoder tokens`() {
        val roles = SherpaBackend.resolveRoles(variant(
            "turbo",
            "turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"))
        assertEquals("turbo-encoder.int8.onnx", roles.encoder)
        assertEquals("turbo-decoder.int8.onnx", roles.decoder)
        assertEquals("turbo-tokens.txt", roles.tokens)
        assertNull(roles.joiner)
        assertNull(roles.convFrontend)
    }

    @Test
    fun `whisper distil-large-v3 prefixed names resolve`() {
        val roles = SherpaBackend.resolveRoles(variant(
            "distil-large-v3-it",
            "distil-large-v3-it-encoder.int8.onnx",
            "distil-large-v3-it-decoder.int8.onnx",
            "distil-large-v3-it-tokens.txt",
        ))
        assertEquals("distil-large-v3-it-encoder.int8.onnx", roles.encoder)
        assertEquals("distil-large-v3-it-decoder.int8.onnx", roles.decoder)
        assertEquals("distil-large-v3-it-tokens.txt", roles.tokens)
    }

    @Test
    fun `whisper medium and small prefixed names resolve`() {
        for (prefix in listOf("medium", "small")) {
            val roles = SherpaBackend.resolveRoles(variant(
                prefix,
                "$prefix-encoder.int8.onnx", "$prefix-decoder.int8.onnx", "$prefix-tokens.txt"))
            assertEquals("$prefix-encoder.int8.onnx", roles.encoder)
            assertEquals("$prefix-decoder.int8.onnx", roles.decoder)
            assertEquals("$prefix-tokens.txt", roles.tokens)
        }
    }

    @Test
    fun `qwen3 conv-frontend and tokenizer subdir resolve`() {
        val roles = SherpaBackend.resolveRoles(variant(
            "qwen3-4b",
            "conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
            "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json",
        ))
        assertEquals("conv_frontend.onnx", roles.convFrontend)
        assertEquals("encoder.int8.onnx", roles.encoder)
        assertEquals("decoder.int8.onnx", roles.decoder)
        assertEquals("tokenizer", roles.tokenizerDir)
        assertNull(roles.joiner)
    }

    @Test
    fun `nemotron streaming canonical names resolve as default modelType roles`() {
        val roles = SherpaBackend.resolveRoles(
            variant("nemotron", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))
        assertEquals("encoder.int8.onnx", roles.encoder)
        assertEquals("decoder.int8.onnx", roles.decoder)
        assertEquals("joiner.int8.onnx", roles.joiner)
        assertEquals("tokens.txt", roles.tokens)
    }

    // ---- Role invariants: contains-matching, never list position ----

    @Test
    fun `roles resolve from names regardless of listing order`() {
        val shuffled = variant(
            "shuffled",
            "tokens.txt", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx")
        val roles = SherpaBackend.resolveRoles(shuffled)
        assertEquals("encoder.int8.onnx", roles.encoder)
        assertEquals("decoder.int8.onnx", roles.decoder)
        assertEquals("joiner.int8.onnx", roles.joiner)
        assertEquals("tokens.txt", roles.tokens)
    }

    @Test
    fun `encoder resolution prefers an encoder-marked file over earlier list entries`() {
        // A decoder-marked name listed first must not steal the encoder role.
        val roles = SherpaBackend.resolveRoles(
            variant("precedence", "decoder.int8.onnx", "encoder.int8.onnx", "tokens.txt"))
        assertEquals("encoder.int8.onnx", roles.encoder)
        assertEquals("decoder.int8.onnx", roles.decoder)
    }

    @Test
    fun `missing encoder file fails role resolution`() {
        assertThrows(IllegalStateException::class.java) {
            SherpaBackend.resolveRoles(variant("broken", "decoder.int8.onnx", "tokens.txt"))
        }
    }

    // ---- End-to-end: the bundled catalog resolves roles for every variant ----

    @Test
    fun `every bundled catalog variant resolves its required roles`() {
        val catalog = parseRealCatalog()
        val backendIds = listOf("sherpa-onnx", "whisper", "qwen3-asr", "nemotron-streaming", "gigaam")
        for (entryId in backendIds) {
            val entry = requireNotNull(catalog.firstOrNull { it.id == entryId }) { "catalog missing $entryId" }
            for (variant in entry.variants) {
                val roles = SherpaBackend.resolveRoles(variant)
                for (role in SherpaBackend.requiredRoleNames(entry.modelType)) {
                    roles.requireRole(role)
                }
            }
        }
    }

    private fun parseRealCatalog() =
        ModelCatalogJson.parseCatalog(readRealAsset().readText())

    private fun readRealAsset(): File = RepoFiles.resolve("src/main/assets/models_catalog.json")
}