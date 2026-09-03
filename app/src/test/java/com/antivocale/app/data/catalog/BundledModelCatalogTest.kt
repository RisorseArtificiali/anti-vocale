package com.antivocale.app.data.catalog

import com.antivocale.app.R
import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.Language
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-fast integrity guard for the bundled catalog asset
 * (`app/src/main/assets/models_catalog.json`): ids must equal the BACKEND_ID
 * constants, resource keys must resolve through [CatalogStringKeys], and the
 * data must stay faithful to the hand-written constants it replaces (sizes,
 * dirs, repos, flags, language sets). Reads the asset from disk; unit tests run
 * with the module directory as working directory (probed relative to it and to
 * the repo root, like StringResourceParityTest).
 */
class BundledModelCatalogTest {

    private fun asset(): File {
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        return when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate models_catalog.json from ${File(".").absolutePath}")
        }
    }

    private fun catalog(): List<CatalogEntry> =
        ModelCatalogJson.parseCatalog(asset().readText())

    @Test
    fun `catalog covers exactly the five built-in sherpa backends with unique ids`() {
        val models = catalog()
        assertEquals(5, models.size)
        assertEquals(BuiltInBackendIds.ALL.toSet(), models.map { it.id }.toSet())
    }

    @Test
    fun `every resource key in the catalog resolves via CatalogStringKeys`() {
        val keys = mutableSetOf<String>()
        fun add(d: CatalogDisplay?) {
            if (d is CatalogDisplay.Resource) keys.add(d.key)
        }
        for (m in catalog()) {
            add(m.display)
            add(m.description)
            m.noteKey?.let { keys.add(it) }
            for (v in m.variants) {
                add(v.title)
                add(v.description)
                v.badgeKey?.let { keys.add(it) }
            }
        }
        assertTrue("catalog should reference some resource keys", keys.isNotEmpty())
        // resolve() throws (fail-fast) on any unmapped key.
        keys.forEach { CatalogStringKeys.resolve(it) }
    }

    @Test
    fun `CatalogStringKeys resolves known keys and rejects unknowns`() {
        assertEquals(R.string.parakeet_name, CatalogStringKeys.resolve("parakeet_name"))
        assertTrue(runCatching { CatalogStringKeys.resolve("no_such_key") }.isFailure)
    }

    @Test
    fun `share aliases match the registry descriptors`() {
        val byId = catalog().associateBy { it.id }
        assertEquals("com.antivocale.app.ShareParakeet", byId.getValue("sherpa-onnx").shareAlias)
        assertEquals("com.antivocale.app.ShareWhisper", byId.getValue("whisper").shareAlias)
        assertEquals("com.antivocale.app.ShareQwen3", byId.getValue("qwen3-asr").shareAlias)
        assertEquals("com.antivocale.app.ShareNemotron", byId.getValue("nemotron-streaming").shareAlias)
        assertEquals("com.antivocale.app.ShareGigaam", byId.getValue("gigaam").shareAlias)
    }

    @Test
    fun `flags capture the formalized per-model workarounds`() {
        val byId = catalog().associateBy { it.id }

        val parakeet = byId.getValue("sherpa-onnx")
        assertEquals("smoothquant", parakeet.flags.defaultVariant)
        assertEquals(1.0, parakeet.flags.tailPadSeconds, 0.0)
        assertEquals(listOf("vocab_size", "subsampling_factor", "model_type"), parakeet.flags.metaKeys)
        assertEquals("parakeet-tdt-0.6b-v3-smoothquant", parakeet.defaultVariant.dirName)
        assertEquals(862L, parakeet.defaultVariant.estimatedSizeMB)
        // TASK-406: 380s single-pass peaked at 5.2GiB and 120s chunks still peaked at
        // 2.8GiB across sequential decodes; 60s chunks measured 1.8GiB end-to-end.
        // TranscriptionMemoryPolicy additionally tightens per device below this.
        assertEquals(60, parakeet.flags.chunkDurationSeconds)
        // No declared per-pass cap (ModelInfoProvider's pre-catalog null).
        assertEquals(0, parakeet.flags.maxAudioDurationSeconds)

        val qwen3 = byId.getValue("qwen3-asr")
        assertTrue(qwen3.flags.ensureParentDirs)
        assertTrue(qwen3.variants.single().files.any { it.name.startsWith("tokenizer/") })
        assertEquals(1.0, qwen3.flags.blankPenalty, 0.0)
        assertEquals(2048, qwen3.flags.maxNewTokens)
        assertEquals(30, qwen3.flags.chunkDurationSeconds)
        assertEquals(30, qwen3.flags.maxAudioDurationSeconds)
        assertEquals("sherpa-onnx-qwen3-asr-0.6b-int8", qwen3.defaultVariant.dirName)
        assertEquals(938L, qwen3.defaultVariant.estimatedSizeMB)

        val nemotron = byId.getValue("nemotron-streaming")
        assertTrue(nemotron.isStreaming)
        assertEquals("", nemotron.modelType)
        assertTrue(nemotron.flags.languageOption)
        assertEquals(1.5, nemotron.flags.tailPadSeconds, 0.0)
        assertEquals(0, nemotron.flags.maxAudioDurationSeconds)
        assertEquals("nemotron-3.5-asr-streaming-0.6b-1120ms-int8", nemotron.defaultVariant.dirName)
        assertEquals(640L, nemotron.defaultVariant.estimatedSizeMB)

        val gigaam = byId.getValue("gigaam")
        assertEquals(1.0, gigaam.flags.tailPadSeconds, 0.0)
        assertEquals(0, gigaam.flags.maxAudioDurationSeconds)
        assertEquals(listOf("vocab_size", "subsampling_factor", "model_type"), gigaam.flags.metaKeys)
        assertEquals("pantinor/gigaam-v3", gigaam.defaultVariant.source.repo)
        assertEquals("gigaam-v3", gigaam.defaultVariant.dirName)
        assertEquals(326L, gigaam.defaultVariant.estimatedSizeMB)
        assertTrue("gigaam files must keep their SHA-256 pins", gigaam.defaultVariant.files.all { it.sha256 != null })
    }

    @Test
    fun `whisper variants carry their exact files and sizes`() {
        val whisper = catalog().first { it.id == "whisper" }
        assertEquals("small", whisper.flags.defaultVariant)
        assertTrue(whisper.flags.skipMetadataCheck)
        assertEquals(1000, whisper.flags.whisperTailPaddings)
        assertEquals(30, whisper.flags.chunkDurationSeconds)
        assertEquals(30, whisper.flags.maxAudioDurationSeconds)
        val byName = whisper.variants.associateBy { it.name }
        assertEquals("sherpa-onnx-whisper-small", byName.getValue("small").dirName)
        assertEquals(358L, byName.getValue("small").estimatedSizeMB)
        assertEquals(
            listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
            byName.getValue("small").files.map { it.name },
        )
        assertEquals(988L, byName.getValue("turbo").estimatedSizeMB)
        assertEquals(903L, byName.getValue("medium").estimatedSizeMB)
        assertEquals(938L, byName.getValue("distil-large-v3-it").estimatedSizeMB)
        assertEquals("pantinor/sherpa-onnx-whisper-small", byName.getValue("small").source.repo)
    }

    @Test
    fun `language sets match the curated Language constants`() {
        val byId = catalog().associateBy { it.id }
        assertEquals(Language.PARAKEET, byId.getValue("sherpa-onnx").languages.toSet())
        assertEquals(Language.GIGAAM, byId.getValue("gigaam").languages.toSet())
        assertEquals(Language.NEMOTRON, byId.getValue("nemotron-streaming").languages.toSet())
        assertEquals(Language.QWEN3_ASR, byId.getValue("qwen3-asr").languages.toSet())
        assertEquals(Language.WHISPER_MULTILINGUAL, byId.getValue("whisper").variant("small")!!.languages.toSet())
        assertEquals(Language.WHISPER_DISTIL_IT, byId.getValue("whisper").variant("distil-large-v3-it")!!.languages.toSet())
    }

    @Test
    fun `preferUiLanguage is declared on whisper small only`() {
        // TASK-434: the locale-following transcription-language default is
        // opt-in per variant; only Small (unreliable language auto-detection)
        // carries it. Every other variant in the whole catalog stays unflagged.
        val flagged = catalog()
            .flatMap { entry -> entry.variants.map { entry.id to it } }
            .filter { it.second.preferUiLanguage }
        assertEquals(listOf("whisper" to "small"), flagged.map { it.first to it.second.name })
    }

    @Test
    fun `every variant is well-formed and resolvable`() {
        for (m in catalog()) {
            assertTrue("${m.id}: runtime", m.runtime == "offline" || m.runtime == "online")
            assertTrue("${m.id}: modelType required", m.modelType.isNotBlank() || m.isStreaming)
            assertTrue("${m.id}: variants", m.variants.isNotEmpty())
            for (v in m.variants) {
                assertTrue("${m.id}/${v.name}: dirName", v.dirName.isNotBlank())
                assertTrue("${m.id}/${v.name}: size", v.estimatedSizeMB > 0)
                assertTrue("${m.id}/${v.name}: files", v.files.isNotEmpty())
                assertTrue("${m.id}/${v.name}: source kind", v.source.kind == "huggingface" || v.source.kind == "url")
                if (v.source.kind == "huggingface") {
                    assertTrue("${m.id}/${v.name}: repo", !v.source.repo.isNullOrBlank())
                }
                v.files.forEach { f ->
                    if (f.sha256 != null) {
                        assertEquals("${m.id}/${v.name}/${f.name}: 64-hex sha", 64, f.sha256.length)
                    }
                }
            }
        }
    }
}