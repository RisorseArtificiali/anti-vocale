package com.antivocale.app.transcription

import com.antivocale.app.data.catalog.BundledCatalog
import com.antivocale.app.data.catalog.ModelCatalogJson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * GH #44/#50: Parakeet TDT has a hard 400s attention cap (max_position_embeddings
 * 5000 at 12.5 fps). The catalog must chunk it below that limit so long inputs
 * never reach the native Add_2 failure; the chunking pipeline already exists for
 * Whisper/Qwen3 via the same flag.
 */
class ParakeetCatalogChunkingTest {

    private fun catalog() {
        val moduleRelative = File("src/main/assets/models_catalog.json")
        val rootRelative = File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("Cannot locate models_catalog.json")
        }
        BundledCatalog.seed(ModelCatalogJson.parseCatalog(asset.readText()))
    }

    @Test
    fun `parakeet chunks below the 400s native cap`() {
        catalog()
        val parakeet = BundledCatalog.byId(BuiltInBackendIds.PARAKEET)!!
        val chunkSeconds = parakeet.flags.chunkDurationSeconds

        assertTrue("parakeet must enable chunking", chunkSeconds > 0)
        // 12.5 fps * 5000 positions = 400s hard cap; stay safely under it.
        // TASK-406 tightened the default well below that (attention peak is O(T^2):
        // the former 380s cap peaked at 5.2GiB; 120s chunks still 2.8GiB; 60s measured
        // 1.8GiB end-to-end). The exact value is pinned in BundledModelCatalogTest;
        // here we keep the native-cap invariant.
        assertTrue(
            "chunk size $chunkSeconds must stay under the 400s cap with margin",
            chunkSeconds in 30..390,
        )
    }

    @Test
    fun `whisper and qwen3 keep their 30s chunking`() {
        catalog()
        assertEquals(30, BundledCatalog.byId(BuiltInBackendIds.WHISPER)!!.flags.chunkDurationSeconds)
        assertEquals(30, BundledCatalog.byId(BuiltInBackendIds.QWEN3_ASR)!!.flags.chunkDurationSeconds)
    }
}
