package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GH #43: the two-pass eligibility gate. Every condition that must hold for
 * a dual run, and the exact degradation when one does not (the request runs
 * single-model; null means "no first pass").
 */
class DualRefinementPolicyTest {

    private fun fast(
        requestType: String = "audio",
        override: String? = null,
        enabled: Boolean = true,
        selected: String = "parakeet",
        streaming: String? = "nemotron-streaming",
    ) = DualRefinementPolicy.fastBackendFor(
        requestType = requestType,
        backendOverride = override,
        refinementEnabled = enabled,
        selectedBackendId = selected,
        streamingBackendId = streaming,
    )

    @Test
    fun `a qualifying audio request picks the streaming backend`() {
        assertEquals("nemotron-streaming", fast())
    }

    @Test
    fun `non-audio requests never qualify`() {
        assertNull(fast(requestType = "text"))
        assertNull(fast(requestType = "subtitles"))
    }

    @Test
    fun `an explicit override is a deliberate single-model choice`() {
        assertNull(fast(override = "whisper"))
    }

    @Test
    fun `the toggle gates everything and defaults off in the preference`() {
        assertNull(fast(enabled = false))
    }

    @Test
    fun `no installed streaming backend means no first pass`() {
        assertNull(fast(streaming = null))
    }

    @Test
    fun `the streaming model itself as selected backend never pairs with itself`() {
        assertNull(fast(selected = "nemotron-streaming"))
    }

    @Test
    fun `the shipped catalog actually carries a streaming entry the gate can pick`() {
        // The gate's streaming side is catalog-driven; if no entry ever sets
        // isStreaming, DualRefinementPolicy can only return null and the
        // whole feature silently no-ops. Pin the assumption against the real
        // asset (seeded by the test base like every orchestrator test).
        val moduleRelative = java.io.File("src/main/assets/models_catalog.json")
        val rootRelative = java.io.File("app/src/main/assets/models_catalog.json")
        val asset = when {
            moduleRelative.exists() -> moduleRelative
            rootRelative.exists() -> rootRelative
            else -> throw IllegalStateException("cannot locate models_catalog.json")
        }
        com.antivocale.app.data.catalog.BundledCatalog.seed(
            com.antivocale.app.data.catalog.ModelCatalogJson.parseCatalog(asset.readText()))
        val streaming = com.antivocale.app.data.catalog.BundledCatalog.entries()
            .filter { it.isStreaming }
        org.junit.Assert.assertTrue(
            "no isStreaming entry in the shipped catalog: the dual gate can never fire",
            streaming.isNotEmpty())
        org.junit.Assert.assertEquals(
            "nemotron-streaming is the streaming entry the design names",
            "nemotron-streaming", streaming.first().id)
    }

    @Test
    fun `skip tokens are stable strings for the persisted context`() {
        // They ride ProcessingContext.refinementSkipReason and the metadata
        // line; renaming one silently orphanes every recorded row.
        assertEquals("fast_load_failed", DualRefinementPolicy.SKIP_FAST_LOAD_FAILED)
        assertEquals("fast_blank", DualRefinementPolicy.SKIP_FAST_BLANK)
        assertEquals("refine_load_failed", DualRefinementPolicy.SKIP_REFINE_LOAD_FAILED)
        assertEquals("refine_inference_failed", DualRefinementPolicy.SKIP_REFINE_INFERENCE_FAILED)
        assertEquals("refine_loop_detected", DualRefinementPolicy.SKIP_REFINE_LOOP)
    }
}
