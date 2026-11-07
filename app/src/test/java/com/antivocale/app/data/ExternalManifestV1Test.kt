package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the schema-1 CDN-manifest import dialect (2026-11-07). The
 * fixtures are TRIMMED COPIES of the live manifests the feature shipped
 * against (Fritter, Fritter-streaming on models.carrotcakeai.com, Sabich on
 * cdn.hebrish.app): these fixtures pin our parser against the captured real
 * shape; publisher drift is caught at import time by the live fetch, not
 * here. The logic under test is the pure [ExternalManifestV1] object (sniff
 * + synthesize), deliberately kept free of Android and network so these run
 * on the plain JVM.
 */
class ExternalManifestV1Test {

    companion object {
        /** Fritter: extra bpe.vocab file inside "files" AND a "gguf" section. */
        private const val FRITTER = """{
 "schema": 1,
 "model": "fritter",
 "version": "Fritter v1.2",
 "revision": "9836ee57f85abc0c9af0343ffd61528f322da8c3",
 "source": "https://huggingface.co/danielrosehill/Fritter",
 "licence": "CC-BY-NC-4.0",
 "base_url": "https://models.carrotcakeai.com/asr/fritter/latest/",
 "files": {
  "encoder.int8.onnx": {
   "size": 652282303,
   "sha256": "b1924e35604a5dd4c4f10867d5109cb894729768041e7861431b53773520be9f"
  },
  "decoder.int8.onnx": {
   "size": 7257753,
   "sha256": "d79eac8bba592a6efd1343591c0724648bf1a1ffa035bad4699e349501a1a600"
  },
  "joiner.int8.onnx": {
   "size": 1739080,
   "sha256": "37b5e174ee6b4b9e8f48a770d01bb172c3a83cef1bb7e28f3a012a282ac5876e"
  },
  "tokens.txt": {
   "size": 9384,
   "sha256": "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"
  },
  "bpe.vocab": {
   "size": 12441,
   "sha256": "23286427234a08393da0a6511bbccaf8edab3dc77378279d8b129137a235dbbb"
  }
 },
 "gguf": {
  "Fritter-F16.gguf": {
   "size": 1237334624,
   "sha256": "73e3696158edd0f6b3d7d6841a2db350a29fbecb35862f41697a5a5a1b4c5ad7"
  }
 }
}"""

        /** Sabich: licence lives under "terms", and the extra "onnx" section
         *  carries a DIFFERENT merged layout (files as a size-number map, a
         *  decoder_joint-model merged file set) that must be ignored. */
        private const val SABICH = """{
 "schema": 1,
 "model": "sabich",
 "terms": "https://hebrish.app/terms",
 "version": "Sabich v3.0",
 "revision": "86dd4a2203c3a6e7e5be44bcd3352d346427a2c8",
 "base_url": "https://cdn.hebrish.app/models/sabich/",
 "files": {
  "encoder.int8.onnx": {
   "size": 652282307,
   "sha256": "4f528da717fb762a2c31795466378faf3865360d4d8f92f0cc7370da2d1e9f93"
  },
  "decoder.int8.onnx": {
   "size": 7257753,
   "sha256": "bcdfcbce8e9b0bf10d36ad7a09e9b85e2de0c29b7e2acb72e0d025ce6c5b5349"
  },
  "joiner.int8.onnx": {
   "size": 1739080,
   "sha256": "c371189705cb9713212086e1ff42a380d3b6411a3e119666508dfd824c226cdb"
  },
  "tokens.txt": {
   "size": 9384,
   "sha256": "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"
  }
 },
 "onnx": {
  "run": "r29-roll",
  "base_url": "https://cdn.hebrish.app/models/sabich/onnx/",
  "files": {
   "vocab.txt": 9384,
   "config.json": 97,
   "decoder_joint-model.int8.onnx": 8998284,
   "encoder-model.int8.onnx": 652282025
  }
 }
}"""

        /** Fritter Streaming (round 2): Nemotron 0.6B streaming transducer; extra
         *  NOTICE / license-PDF files inside "files" and a "gguf" section. The
         *  manifest itself carries NO streaming field: the index entry authors
         *  the flag, and the "model" name is the streaming tell. */
        private const val FRITTER_STREAMING = """{
 "schema": 1,
 "model": "fritter-streaming",
 "version": "Fritter Nemotron v1.0",
 "licence": "CC-BY-NC-4.0",
 "base_url": "https://models.carrotcakeai.com/asr/fritter-streaming/latest/",
 "files": {
  "encoder.int8.onnx": {
   "size": 652916816,
   "sha256": "d2cc3f9c06d454e551997b178193e4b011525c291a08968699e7893f5ce7a199"
  },
  "decoder.int8.onnx": {
   "size": 7257753,
   "sha256": "b143052dd5411aabbb709b45adfed3a7f8f47aa9d928ced2023e858e513b2887"
  },
  "joiner.int8.onnx": {
   "size": 1735862,
   "sha256": "95767b42cd3c3b726d8795d0efb55bce889f774651ac34abcb8a36b72fab90a0"
  },
  "tokens.txt": {
   "size": 8952,
   "sha256": "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"
  },
  "NOTICE": {
   "size": 369,
   "sha256": "b3e07f85ba4904dd45bb4b4798ce0cea33a5ff9a7cbba36d5761db6a9f0bc5dc"
  },
  "NVIDIA-Open-Model-License.pdf": {
   "size": 79005,
   "sha256": "4d2fb590aa9b30c47f2058bff17291df7fa2aa0c1bd775a20703da9bb267cfab"
  }
 },
 "gguf": {
  "Fritter-Nemotron-0.6B-F16.gguf": {
   "size": 1237652608,
   "sha256": "dd6fa9820ae2b32315aa46189e2c8ec1ff91be77adc860c40f106d38142f2cbc"
  }
 }
}"""

        /** A legacy catalog-entry JSON (array "files"): the arm the sniff must NOT touch. */
        private const val LEGACY_ENTRY = """{
 "name": "Zipformer Vietnamese",
 "languages": ["vi"],
 "family": "TRANSDUCER",
 "modelType": "",
 "files": [
  {"name": "encoder.int8.onnx", "url": "https://example.com/enc.onnx",
   "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1}
 ]
}"""
    }

    @Test
    fun `sniff accepts both live manifests`() {
        assertTrue(ExternalManifestV1.sniff(FRITTER))
        assertTrue(ExternalManifestV1.sniff(SABICH))
        assertTrue(ExternalManifestV1.sniff(FRITTER_STREAMING))
    }

    @Test
    fun `a streaming-named manifest without the streaming field rejects a bare import`() {
        // Absent field: the model-name heuristic is the fallback (the
        // fritter-streaming pattern at fixture time), and a streaming model
        // can ONLY arrive through an index entry authoring the flag; a direct
        // import must fail loudly instead of registering an offline record
        // that dies at first decode.
        try {
            ExternalManifestV1.synthesize(
                FRITTER_STREAMING, "https://models.carrotcakeai.com/asr/fritter-streaming/latest/manifest.json")
            fail("a bare import of a streaming-named manifest must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue("error must name the model: ${e.message}",
                e.message!!.contains("fritter-streaming"))
            assertTrue("error must point at the catalog entry: ${e.message}",
                e.message!!.contains("catalog entry"))
        }
        // An offline-named manifest stays importable bare (the round-1 arms).
        ExternalManifestV1.synthesize(FRITTER, "u")
    }

    @Test
    fun `an explicit streaming field wins over the name heuristic both ways`() {
        // Present and TRUE: rejected bare even though the name alone is what
        // the old heuristic needed (field true on an offline-named model too).
        val fieldTrue = FRITTER.replace("\"licence\": \"CC-BY-NC-4.0\",",
            "\"licence\": \"CC-BY-NC-4.0\",\n \"streaming\": true,")
        try {
            ExternalManifestV1.synthesize(fieldTrue, "u")
            fail("an explicit streaming:true manifest must reject a bare import")
        } catch (e: IllegalArgumentException) {
            assertTrue("error must point at the catalog entry: ${e.message}",
                e.message!!.contains("catalog entry"))
        }
        // Present and FALSE on a streaming-NAMED model: bare-importable as
        // offline, with the offline modelType (the field wins over the name).
        val fieldFalse = FRITTER_STREAMING.replace(
            "\"model\": \"fritter-streaming\",",
            "\"model\": \"fritter-streaming\",\n \"streaming\": false,")
        val entry = ExternalManifestV1.synthesize(fieldFalse, "u")
        assertFalse(entry.streaming)
        assertEquals("nemo_transducer", entry.modelType)
        assertEquals("fritter-streaming", entry.name)
    }

    @Test
    fun `an index streaming flag synthesizes the streaming entry with the empty modelType`() {
        val entry = ExternalManifestV1.synthesize(
            FRITTER_STREAMING, "u",
            overrideName = "Fritter Streaming English int8 (Nemotron 0.6B, CC-BY-NC)",
            overrideLanguages = listOf("en"),
            overrideStreaming = true)
        assertTrue(entry.streaming)
        // The bundled-Nemotron pattern: a streaming NeMo encoder carries only
        // vocab_size, so the synthesized entry must NOT demand the
        // nemo_transducer metadata trio (modelType "") and the backend builds
        // the OnlineRecognizer with the same empty modelType.
        assertEquals("", entry.modelType)
        assertEquals(ModelFamily.TRANSDUCER, entry.family)
        assertEquals("CC-BY-NC-4.0", entry.licence)
        // The extra NOTICE / license PDF files are ignored like bpe.vocab.
        assertEquals(4, entry.files.size)
        assertEquals("https://models.carrotcakeai.com/asr/fritter-streaming/latest/encoder.int8.onnx",
            entry.files.first { it.name == "encoder.int8.onnx" }.url)
        // The offline default keeps the NeMo modelType.
        assertEquals("nemo_transducer", ExternalManifestV1.synthesize(FRITTER, "u").modelType)
    }

    @Test
    fun `a streaming flag on a non-transducer override family is rejected`() {
        try {
            ExternalManifestV1.synthesize(
                FRITTER_STREAMING, "u",
                overrideFamily = ModelFamily.WHISPER, overrideStreaming = true)
            fail("streaming is TRANSDUCER-only")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("TRANSDUCER"))
        }
    }

    @Test
    fun `an explicit model_type overrides the offline default and is family-validated`() {
        // R4: a manifest may declare its model_type. A k2 zipformer declares
        // the EMPTY string (the plain-transducer loader; the same value the
        // catalog's zipformer entries carry); a non-default valid value rides
        // through; an invalid one rejects at the family gate.
        val zipformer = FRITTER.replace("\"licence\": \"CC-BY-NC-4.0\",",
            "\"licence\": \"CC-BY-NC-4.0\",\n \"model_type\": \"\",")
        assertEquals("", ExternalManifestV1.synthesize(zipformer, "u").modelType)
        val conformer = FRITTER.replace("\"licence\": \"CC-BY-NC-4.0\",",
            "\"licence\": \"CC-BY-NC-4.0\",\n \"model_type\": \"conformer_transducer\",")
        assertEquals("conformer_transducer", ExternalManifestV1.synthesize(conformer, "u").modelType)
        val bogus = FRITTER.replace("\"licence\": \"CC-BY-NC-4.0\",",
            "\"licence\": \"CC-BY-NC-4.0\",\n \"model_type\": \"bogus\",")
        try {
            ExternalManifestV1.synthesize(bogus, "u")
            fail("an invalid manifest model_type must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bogus"))
        }
        // Absent field keeps the NeMo default (the shipped behavior).
        assertEquals("nemo_transducer", ExternalManifestV1.synthesize(FRITTER, "u").modelType)
        // The streaming arm ignores the field: modelType stays "".
        val streamingWithType = FRITTER_STREAMING.replace(
            "\"licence\": \"CC-BY-NC-4.0\",",
            "\"licence\": \"CC-BY-NC-4.0\",\n \"model_type\": \"nemo_transducer\",")
        assertEquals("", ExternalManifestV1.synthesize(streamingWithType, "u", overrideStreaming = true).modelType)
    }

    @Test
    fun `a declared split-onnx sidecar joins the synthesized file set`() {
        // R7: a manifest-declared encoder.int8.onnx.data sibling is planned
        // like any other file, so the sidecar scanner's import-set guidance
        // stays satisfiable for publishers shipping external-data encoders.
        val encoderData = "1111111111111111111111111111111111111111111111111111111111111111"
        val withSidecar = FRITTER.replace(
            "  \"bpe.vocab\": {",
            "  \"encoder.int8.onnx.data\": {\n   \"size\": 4096,\n   \"sha256\": \"$encoderData\"\n  },\n  \"bpe.vocab\": {")
        val entry = ExternalManifestV1.synthesize(withSidecar, "u")
        assertEquals(5, entry.files.size)
        val sidecar = entry.files.first { it.name == "encoder.int8.onnx.data" }
        assertEquals("https://models.carrotcakeai.com/asr/fritter/latest/encoder.int8.onnx.data", sidecar.url)
        assertEquals(4096L, sidecar.size)
        // The four required names are all still there.
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
            "encoder.int8.onnx.data"), entry.files.map { it.name }.toSet())
        // An unpinned sidecar rejects like any other file.
        val unpinned = withSidecar.replace("\"sha256\": \"$encoderData\"", "\"sha256\": \"\"")
        try {
            ExternalManifestV1.synthesize(unpinned, "u")
            fail("a hashless sidecar must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("encoder.int8.onnx.data"))
        }
    }


    @Test
    fun `sniff rejects entry json, index json, and garbage`() {
        // entry JSON: "files" is an ARRAY, not an object
        assertFalse(ExternalManifestV1.sniff(LEGACY_ENTRY))
        assertFalse(ExternalManifestV1.sniff("""{"entries": [{"name": "x"}]}"""))
        assertFalse(ExternalManifestV1.sniff("not json at all"))
        // schema present but no base_url or files object
        assertFalse(ExternalManifestV1.sniff("""{"schema": 1, "model": "x"}"""))
        assertFalse(ExternalManifestV1.sniff("""{"schema": 2, "base_url": "https://x/", "files": {}}"""))
    }

    @Test
    fun `synthesis joins the four required files onto base_url with the manifest pins`() {
        val entry = ExternalManifestV1.synthesize(
            FRITTER, "https://models.carrotcakeai.com/asr/fritter/latest/manifest.json")
        assertEquals("fritter", entry.name)
        assertEquals(ModelFamily.TRANSDUCER, entry.family)
        assertEquals("nemo_transducer", entry.modelType)
        assertFalse(entry.streaming)
        val byName = entry.files.associateBy { it.name }
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            byName.keys)
        assertEquals("https://models.carrotcakeai.com/asr/fritter/latest/encoder.int8.onnx",
            byName.getValue("encoder.int8.onnx").url)
        assertEquals("https://models.carrotcakeai.com/asr/fritter/latest/tokens.txt",
            byName.getValue("tokens.txt").url)
        assertEquals(652282303L, byName.getValue("encoder.int8.onnx").size)
        assertEquals("b1924e35604a5dd4c4f10867d5109cb894729768041e7861431b53773520be9f",
            byName.getValue("encoder.int8.onnx").sha256)
    }

    @Test
    fun `extra filenames and the gguf section are ignored`() {
        val entry = ExternalManifestV1.synthesize(
            FRITTER, "https://models.carrotcakeai.com/asr/fritter/latest/manifest.json")
        // bpe.vocab stays out of the download set; the gguf section too.
        assertEquals(4, entry.files.size)
        assertTrue(entry.files.none { it.name.endsWith(".gguf") || it.name == "bpe.vocab" })
    }

    @Test
    fun `sabich merged onnx section is ignored and the top-level files win`() {
        val entry = ExternalManifestV1.synthesize(
            SABICH, "https://cdn.hebrish.app/models/sabich/latest.json")
        // The onnx section's files (vocab.txt, config.json, decoder_joint-model...) and
        // its DIFFERENT base_url (/onnx/) must never reach the download set.
        assertEquals(4, entry.files.size)
        assertTrue(entry.files.all { it.url.startsWith("https://cdn.hebrish.app/models/sabich/") &&
            !it.url.contains("/onnx/") })
        assertEquals("https://cdn.hebrish.app/models/sabich/decoder.int8.onnx",
            entry.files.first { it.name == "decoder.int8.onnx" }.url)
        assertEquals("sabich", entry.name)
    }

    @Test
    fun `licence comes from licence, terms, and the index override in that order`() {
        assertEquals("CC-BY-NC-4.0", ExternalManifestV1.synthesize(
            FRITTER, "u").licence)
        // Sabich has no "licence" field: the "terms" URL is the fallback.
        assertEquals("https://hebrish.app/terms", ExternalManifestV1.synthesize(
            SABICH, "u").licence)
        // The index-authored licence (an index pick) merges OVER the manifest's.
        assertEquals("custom", ExternalManifestV1.synthesize(
            FRITTER, "u", overrideLicence = "custom").licence)
    }

    @Test
    fun `index metadata merges over the manifest-derived values`() {
        val entry = ExternalManifestV1.synthesize(
            FRITTER, "u",
            overrideName = "Fritter English int8 (Parakeet TDT v2 fine-tune, CC-BY-NC)",
            overrideLanguages = listOf("en"))
        assertEquals("Fritter English int8 (Parakeet TDT v2 fine-tune, CC-BY-NC)", entry.name)
        assertEquals(listOf("en"), entry.languages)
        // Without overrides the name comes from "model" and languages stay empty
        // (the record derives hints from the display name, the same rule as
        // every other import path).
        val bare = ExternalManifestV1.synthesize(SABICH, "u")
        assertEquals("sabich", bare.name)
        assertEquals(emptyList<String>(), bare.languages)
    }

    @Test
    fun `a missing required file is rejected naming it`() {
        val broken = FRITTER.replace(
            Regex("\"joiner\\.int8\\.onnx\":\\s*\\{[^}]*\\},?"), "")
        try {
            ExternalManifestV1.synthesize(broken, "https://x/manifest.json")
            fail("missing joiner must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue("error must name joiner.int8.onnx: ${e.message}",
                e.message!!.contains("joiner.int8.onnx"))
        }
    }

    @Test
    fun `an unpinned or sizeless manifest file is rejected`() {
        val noSha = FRITTER.replace(
            "\"sha256\": \"37b5e174ee6b4b9e8f48a770d01bb172c3a83cef1bb7e28f3a012a282ac5876e\"",
            "\"sha256\": \"\"")
        try {
            ExternalManifestV1.synthesize(noSha, "u")
            fail("hashless manifest file must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("joiner.int8.onnx"))
        }
        val noSize = FRITTER.replace("\"size\": 7257753,", "")
        try {
            ExternalManifestV1.synthesize(noSize, "u")
            fail("sizeless manifest file must reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("decoder.int8.onnx"))
        }
    }

    @Test
    fun `base_url without a trailing slash still joins cleanly`() {
        val noSlash = FRITTER.replace(
            "\"base_url\": \"https://models.carrotcakeai.com/asr/fritter/latest/\"",
            "\"base_url\": \"https://models.carrotcakeai.com/asr/fritter/latest\"")
        val entry = ExternalManifestV1.synthesize(noSlash, "u")
        assertEquals("https://models.carrotcakeai.com/asr/fritter/latest/encoder.int8.onnx",
            entry.files.first().url)
    }

    @Test
    fun `the existing entry-json arm keeps parsing legacy entries unchanged`() {
        val entry = ExternalModelEntryJson.parse(LEGACY_ENTRY)
        assertEquals("Zipformer Vietnamese", entry.name)
        assertEquals(ModelFamily.TRANSDUCER, entry.family)
        assertEquals(1, entry.files.size)
        assertNull(entry.licence)
        // and the sniff does not claim it
        assertFalse(ExternalManifestV1.sniff(LEGACY_ENTRY))
    }

    @Test
    fun `the HF repo arm classification is untouched by the sniff`() {
        // A HF repo URL is not a .json url and not a manifest; the importer's
        // classification arm must still route it to the repo listing (pinned
        // via the same helpers the importer branches on).
        assertTrue(ExternalModelImporter.isCatalogEntryUrl(
            "https://models.carrotcakeai.com/asr/fritter/latest/manifest.json"))
        assertTrue(ExternalModelImporter.isCatalogEntryUrl(
            "https://cdn.hebrish.app/models/sabich/latest.json"))
        assertNull(HuggingFaceRepoListing.parseRepoId(
            "https://models.carrotcakeai.com/asr/fritter/latest/manifest.json"))
        assertEquals("owner/repo",
            HuggingFaceRepoListing.parseRepoId("https://huggingface.co/owner/repo"))
    }

    /** The record round-trips its optional licence field (data-only contract). */
    @Test
    fun `record licence survives the json round trip and stays optional`() {
        val base = ExternalModelRecord(
            id = "abc", displayName = "Fritter", dir = "/d", family = ModelFamily.TRANSDUCER,
            modelType = "nemo_transducer", languages = listOf("en"),
            source = ExternalModelSource.URL, sourceUrl = "u",
            files = mapOf("encoder.int8.onnx" to FilePin("f".repeat(64), true)),
            sizeBytes = 1, importedAt = 0L, licence = "CC-BY-NC-4.0")
        val round = ExternalModelRecord.fromJson(base.toJson())!!
        assertEquals("CC-BY-NC-4.0", round.licence)
        // Old records without the field stay parseable, licence null.
        val legacyJson = base.copy(licence = null).toJson()
        legacyJson.remove("licence")
        assertNull(ExternalModelRecord.fromJson(legacyJson)!!.licence)
    }
}
