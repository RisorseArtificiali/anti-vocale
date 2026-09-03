package com.antivocale.app.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unified catalog parser tests: the bundled-catalog document shape (array of
 * built-in entries with variants/sources/flags) and the external single-entry
 * shape (literal name/description, files with mandatory url+sha256+size).
 */
class ModelCatalogTest {

    private val builtInDoc = """
        {
          "schemaVersion": 1,
          "models": [
            {
              "id": "sherpa-onnx",
              "runtime": "offline",
              "modelType": "nemo_transducer",
              "family": "TRANSDUCER",
              "display": { "resourceKey": "parakeet_name" },
              "description": { "resourceKey": "parakeet_description" },
              "shareAlias": "com.antivocale.app.ShareParakeet",
              "storageDir": "parakeet-tdt",
              "flags": {
                "defaultVariant": "smoothquant",
                "tailPadSeconds": 1,
                "metaKeys": ["vocab_size", "subsampling_factor", "model_type"]
              },
              "languages": ["ru", "en"],
              "variants": [
                {
                  "name": "smoothquant",
                  "title": { "resourceKey": "parakeet_smoothquant_title" },
                  "description": { "resourceKey": "parakeet_smoothquant_description" },
                  "dirName": "parakeet-tdt-0.6b-v3-smoothquant",
                  "estimatedSizeMB": 862,
                  "source": { "kind": "huggingface", "repo": "pantinor/parakeet-tdt-0.6b-v3-smoothquant" },
                  "files": ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"]
                },
                {
                  "name": "stock-int8",
                  "title": { "resourceKey": "parakeet_stock_title" },
                  "dirName": "parakeet-tdt-0.6b-v3-int8",
                  "estimatedSizeMB": 464,
                  "source": { "kind": "huggingface", "repo": "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" },
                  "files": ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `built-in catalog doc parses entries, variants, sources and flags`() {
        val models = ModelCatalogJson.parseCatalog(builtInDoc)
        val parakeet = models.single()

        assertEquals("sherpa-onnx", parakeet.id)
        assertEquals("offline", parakeet.runtime)
        assertEquals("nemo_transducer", parakeet.modelType)
        assertEquals("TRANSDUCER", parakeet.family)
        assertEquals(CatalogDisplay.Resource("parakeet_name"), parakeet.display)
        assertEquals(CatalogDisplay.Resource("parakeet_description"), parakeet.description)
        assertEquals("com.antivocale.app.ShareParakeet", parakeet.shareAlias)
        assertEquals("parakeet-tdt", parakeet.storageDir)
        assertEquals(listOf("ru", "en"), parakeet.languages)
        assertEquals(false, parakeet.isStreaming)

        assertEquals("smoothquant", parakeet.flags.defaultVariant)
        assertEquals(1.0, parakeet.flags.tailPadSeconds, 0.0)
        assertEquals(listOf("vocab_size", "subsampling_factor", "model_type"), parakeet.flags.metaKeys)

        assertEquals(2, parakeet.variants.size)
        val smooth = parakeet.variants.first()
        assertEquals(CatalogDisplay.Resource("parakeet_smoothquant_title"), smooth.title)
        assertEquals("parakeet-tdt-0.6b-v3-smoothquant", smooth.dirName)
        assertEquals(862L, smooth.estimatedSizeMB)
        assertEquals("huggingface", smooth.source.kind)
        assertEquals("pantinor/parakeet-tdt-0.6b-v3-smoothquant", smooth.source.repo)
        assertEquals(4, smooth.files.size)
        assertEquals("tokens.txt", smooth.files.last().name)
        assertEquals(null, smooth.files.first().sha256)

        assertEquals("parakeet-tdt-0.6b-v3-smoothquant", parakeet.defaultVariant.dirName)
    }

    @Test
    fun `entry without an explicit display falls back to the first variant title`() {
        val doc = builtInDoc.replace("\"display\": { \"resourceKey\": \"parakeet_name\" },\n", "")
        val parakeet = ModelCatalogJson.parseCatalog(doc).single()
        assertEquals(CatalogDisplay.Resource("parakeet_smoothquant_title"), parakeet.display)
    }

    @Test
    fun `variant preferUiLanguage defaults to false and parses when declared`() {
        // TASK-434: the locale-following default is opt-in per variant.
        val unflagged = ModelCatalogJson.parseCatalog(builtInDoc).single().variants
        assertTrue(unflagged.all { !it.preferUiLanguage })

        val flagged = builtInDoc.replace(
            "\"estimatedSizeMB\": 862,",
            "\"estimatedSizeMB\": 862,\n                  \"preferUiLanguage\": true,",
        )
        val variants = ModelCatalogJson.parseCatalog(flagged).single().variants
        assertTrue(variants.first().preferUiLanguage)
        assertTrue(!variants.last().preferUiLanguage)
    }

    @Test
    fun `schema version mismatch is rejected`() {
        assertTrue(runCatching { ModelCatalogJson.parseCatalog(builtInDoc.replace("1", "2", ignoreCase = false)) }.isFailure)
        assertTrue(runCatching {
            ModelCatalogJson.parseCatalog(builtInDoc.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        }.isFailure)
    }

    @Test
    fun `built-in entry with an invalid runtime is rejected`() {
        assertTrue(runCatching {
            ModelCatalogJson.parseCatalog(builtInDoc.replace("\"runtime\": \"offline\"", "\"runtime\": \"batch\""))
        }.isFailure)
    }

    @Test
    fun `built-in entry without variants is rejected`() {
        val noVariants = """{"schemaVersion":1,"models":[{"id":"x","runtime":"offline","modelType":"nemo_transducer","family":"TRANSDUCER","display":{"resourceKey":"parakeet_name"},"variants":[]}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseCatalog(noVariants) }.isFailure)

        val stripped = """{"schemaVersion":1,"models":[{"id":"x","runtime":"offline","modelType":"nemo_transducer","family":"TRANSDUCER","display":{"resourceKey":"parakeet_name"}}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseCatalog(stripped) }.isFailure)
    }

    // ---- external single-entry shape ----

    @Test
    fun `external entry parses literal name and description with mandatory pins`() {
        val entry = ModelCatalogJson.parseEntry("""
            {"name":"My Model","description":"From a third party","languages":["ru","en"],
             "files":[
               {"name":"my_encoder.onnx","url":"https://x/e.onnx","sha256":"${"a".repeat(64)}","size":100},
               {"name":"decoder.onnx","url":"https://x/d.onnx","sha256":"${"b".repeat(64)}","size":50},
               {"name":"joiner.onnx","url":"https://x/j.onnx","sha256":"${"c".repeat(64)}","size":50},
               {"name":"tokens.txt","url":"https://x/t.txt","sha256":"${"d".repeat(64)}","size":10}]}
        """.trimIndent())

        assertEquals("", entry.id)
        assertEquals(CatalogDisplay.Literal("My Model"), entry.display)
        assertEquals(CatalogDisplay.Literal("From a third party"), entry.description)
        assertEquals("nemo_transducer", entry.modelType)  // defaulted
        assertEquals(listOf("ru", "en"), entry.languages)
        assertEquals(1, entry.variants.size)
        val variant = entry.variants.single()
        assertEquals("default", variant.name)
        assertEquals(4, variant.files.size)
        val first = variant.files.first()
        assertEquals("my_encoder.onnx", first.name)
        assertEquals("https://x/e.onnx", first.url)
        assertEquals("a".repeat(64), first.sha256)
        assertEquals(100L, first.size)
    }

    @Test
    fun `external entry accepts description as an object with text`() {
        val entry = ModelCatalogJson.parseEntry("""
            {"name":"M","description":{"text":"plain"},"files":[
              {"name":"e.onnx","url":"https://x/e","sha256":"${"a".repeat(64)}","size":1}]}
        """.trimIndent())
        assertEquals(CatalogDisplay.Literal("plain"), entry.description)
    }

    @Test
    fun `external entry rejects resource-keyed names, hashless, sizeless and urlless files`() {
        val resourceName = """{"display":{"resourceKey":"parakeet_name"},"files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"a".repeat(64)}","size":1}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(resourceName) }.isFailure)

        val hashless = """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a"}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(hashless) }.isFailure)

        val sizeless = """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a","sha256":"${"a".repeat(64)}"}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(sizeless) }.isFailure)

        val urlless = """{"name":"x","files":[{"name":"a.onnx","sha256":"${"a".repeat(64)}","size":1}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(urlless) }.isFailure)

        val shortSha = """{"name":"x","files":[{"name":"a.onnx","url":"https://x/a","sha256":"abc","size":1}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(shortSha) }.isFailure)
    }

    @Test
    fun `external entry without files is rejected`() {
        assertTrue(runCatching { ModelCatalogJson.parseEntry("""{"name":"x"}""") }.isFailure)
    }

    // ---- source url resolution ----

    @Test
    fun `source resolveUrl builds huggingface and template urls`() {
        val hf = CatalogSource(kind = "huggingface", repo = "pantinor/gigaam-v3")
        assertEquals(
            "https://huggingface.co/pantinor/gigaam-v3/resolve/main/tokens.txt",
            hf.resolveUrl("tokens.txt"),
        )
        val templated = CatalogSource(kind = "url", template = "https://example.com/models/{file}")
        assertEquals("https://example.com/models/encoder.onnx", templated.resolveUrl("encoder.onnx"))
    }

    @Test
    fun `url template must contain the file placeholder`() {
        val noPlaceholder = CatalogSource(kind = "url", template = "https://x/no-placeholder")
        assertTrue(runCatching { noPlaceholder.resolveUrl("encoder.onnx") }.isFailure)

        val blank = CatalogSource(kind = "url", template = "")
        assertTrue(runCatching { blank.resolveUrl("e.onnx") }.isFailure)

        val badKind = runCatching { CatalogSource(kind = "local") }
        assertTrue(badKind.isFailure)

        val hfNoRepo = runCatching { CatalogSource(kind = "huggingface") }
        assertTrue(hfNoRepo.isFailure)
    }

    // ---- flags ----

    @Test
    fun `flags parse every field with defaults`() {
        val entry = ModelCatalogJson.parseEntry("""
            {"name":"M","flags":{"ensureParentDirs":true,"tailPadSeconds":1.5,"languageOption":true,"metaKeys":["a","b"],"skipMetadataCheck":true,"whisperTailPaddings":1000,"blankPenalty":1.0,"maxNewTokens":2048,"chunkDurationSeconds":30,"maxAudioDurationSeconds":45},"files":[
              {"name":"e.onnx","url":"https://x/e","sha256":"${"a".repeat(64)}","size":1}]}
        """.trimIndent())
        val flags = entry.flags
        assertTrue(flags.ensureParentDirs)
        assertEquals(1.5, flags.tailPadSeconds, 0.0)
        assertTrue(flags.languageOption)
        assertEquals(listOf("a", "b"), flags.metaKeys)
        assertEquals(null, flags.defaultVariant)
        assertTrue(flags.skipMetadataCheck)
        assertEquals(1000, flags.whisperTailPaddings)
        assertEquals(1.0, flags.blankPenalty, 0.0)
        assertEquals(2048, flags.maxNewTokens)
        assertEquals(30, flags.chunkDurationSeconds)
        assertEquals(45, flags.maxAudioDurationSeconds)

        val defaults = ModelCatalogJson.parseEntry("""{"name":"D","files":[{"name":"e.onnx","url":"https://x/e","sha256":"${"a".repeat(64)}","size":1}]}""").flags
        assertEquals(CatalogFlags(), defaults)
    }

    @Test
    fun `flag keys the engine does not consume are rejected at parse time`() {
        // Dead flags (e.g. chunkMs, sidecarSize) must fail parse so they cannot
        // re-enter the catalog silently and be stored-but-ignored.
        val unknown = """{"name":"M","flags":{"chunkMs":1120},"files":[{"name":"e.onnx","url":"https://x/e","sha256":"${"a".repeat(64)}","size":1}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseEntry(unknown) }.isFailure)

        val unknownBuiltIn = """{"schemaVersion":1,"models":[{"id":"x","runtime":"offline","modelType":"nemo_transducer","family":"TRANSDUCER","display":{"resourceKey":"parakeet_name"},"flags":{"sidecarSize":true},"variants":[{"name":"v","dirName":"d","estimatedSizeMB":1,"source":{"kind":"url","template":"https://x/{file}"},"files":["a.onnx","b.onnx","c.onnx","t.txt"]}]}]}"""
        assertTrue(runCatching { ModelCatalogJson.parseCatalog(unknownBuiltIn) }.isFailure)
    }

    @Test
    fun `defaultVariant resolves by name and falls back to the first`() {
        val models = ModelCatalogJson.parseCatalog(builtInDoc)
        assertEquals("smoothquant", models.single().defaultVariant.name)
        val noDefault = builtInDoc.replace("\"defaultVariant\": \"smoothquant\",\n", "")
        assertEquals("smoothquant", ModelCatalogJson.parseCatalog(noDefault).single().defaultVariant.name)
        assertEquals("stock-int8", models.single().variant("stock-int8")!!.name)
        assertEquals(null, models.single().variant("nope"))
    }

    @Test
    fun `languagesFor falls back from variant to entry languages`() {
        val models = ModelCatalogJson.parseCatalog(builtInDoc)
        val parakeet = models.single()
        assertEquals(listOf("ru", "en"), parakeet.languagesFor(parakeet.variants.first()))
    }

    @Test
    fun `variantForDirName matches dirName and falls back to the default variant`() {
        val parakeet = ModelCatalogJson.parseCatalog(builtInDoc).single()
        assertEquals(
            "parakeet-tdt-0.6b-v3-int8",
            parakeet.variantForDirName("parakeet-tdt-0.6b-v3-int8").dirName,
        )
        assertEquals(
            "parakeet-tdt-0.6b-v3-smoothquant",
            parakeet.variantForDirName("unknown-dir").dirName,
        )
    }
}