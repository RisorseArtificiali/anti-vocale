package com.antivocale.app.data

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
/**
 * TASK-401 catalog-URL architecture: the resolution matrix of
 * [ExternalCatalogRepository.load] (remote / same-url cache / bundled-asset
 * fallback for the default / hard failure for an unreachable override) and the
 * override validation. Robolectric only for the asset fallback path.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalCatalogRepositoryTest {

    private val defaultUrl = PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL
    private val altUrl = "https://example.org/community/index.json"

    private val index = """{"entries": [
        {"name": "Swiss German", "languages": ["de","gsw"], "family": "WHISPER",
         "entryUrl": "https://example.org/swiss.json"}
    ]}"""
    private val altIndex = """{"entries": [
        {"name": "Alt Model", "languages": ["xx"], "family": "TRANSDUCER",
         "entryUrl": "https://example.org/alt.json"}
    ]}"""

    private fun tempDir(): java.io.File = Files.createTempDirectory("catalog").toFile()

    private fun repo(
        url: String,
        remote: MutableMap<String, String>,
        dir: File,
        context: Context? = null,
    ) = ExternalCatalogRepository(
        context = context ?: org.robolectric.RuntimeEnvironment.getApplication(),
        catalogUrl = { url },
        fetchText = { u -> remote[u] ?: throw IllegalArgumentException("fetch failed: $u") },
        filesDir = { dir },
    )

    @Test
    fun `default url fetches remote and caches`() = runTest {
        val dir = tempDir()
        val remote = mutableMapOf(defaultUrl to index)
        val result = repo(defaultUrl, remote, dir).load().getOrThrow()
        assertEquals(1, result.entries.size)
        assertTrue(result.source is ExternalCatalogRepository.Source.Remote)
        assertFalse((result.source as ExternalCatalogRepository.Source.Remote).isOverride)
        // the good copy is cached on disk
        assertTrue(dir.resolve("catalog").isDirectory)
    }

    @Test
    fun `fetch failure on default falls back to cache then bundled asset`() = runTest {
        val dir = tempDir()
        // seed the cache by a successful load first
        val remote = mutableMapOf(defaultUrl to index)
        repo(defaultUrl, remote, dir).load().getOrThrow()
        // now the network dies: same-url cache is used
        val cached = repo(defaultUrl, mutableMapOf(), dir).load().getOrThrow()
        assertTrue(cached.source is ExternalCatalogRepository.Source.Cached)
        assertEquals(1, cached.entries.size)
        // no cache at all (fresh dir) and offline: bundled asset for the DEFAULT url
        val asset = repo(defaultUrl, mutableMapOf(), tempDir()).load().getOrThrow()
        assertTrue(asset.source is ExternalCatalogRepository.Source.BundledAsset)
        assertTrue(asset.entries.isNotEmpty())
    }

    @Test
    fun `unreachable override with no cache is a hard failure`() = runTest {
        val result = repo(altUrl, mutableMapOf(), tempDir()).load()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unreachable"))
    }

    @Test
    fun `override uses its own cache and never falls back to the default list`() = runTest {
        val dir = tempDir()
        val remote = mutableMapOf(altUrl to altIndex)
        val first = repo(altUrl, remote, dir).load().getOrThrow()
        assertTrue((first.source as ExternalCatalogRepository.Source.Remote).isOverride)
        assertEquals("Alt Model", first.entries.single().name)
        // offline now: the override survives on ITS cache, not on the official list
        val cached = repo(altUrl, mutableMapOf(), dir).load().getOrThrow()
        assertEquals("Alt Model", cached.entries.single().name)
        assertTrue((cached.source as ExternalCatalogRepository.Source.Cached).isOverride)
    }

    @Test
    fun `validateOverride accepts a parsable index and rejects garbage`() = runTest {
        val dir = tempDir()
        val remote = mutableMapOf(altUrl to altIndex)
        val r = repo(defaultUrl, remote, dir)
        assertTrue(r.validateOverride(altUrl))
        assertFalse(r.validateOverride("https://example.org/missing.json"))
    }
}
