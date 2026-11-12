package com.antivocale.app.data.download

import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TASK-986: the pin-override channel's pure pieces - parser, verdict,
 * fallback lookup, heal-store round trip, fetch-once semantics (the fetch
 * is injected; runTest drives it; the store lives in a temp dir).
 */
class PinOverridesTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    private fun entry(key: String, hash: String): JSONObject =
        JSONObject()
            .put("dir", key.substringBefore('/'))
            .put("file", key.substringAfter('/'))
            .put("acceptableHashes", JSONArray().put(hash))

    private fun body(vararg entries: Pair<String, String>): String =
        JSONObject().put(
            "overrides",
            JSONArray().apply { for ((key, hash) in entries) put(entry(key, hash)) },
        ).toString()

    @Test
    fun `parse accepts well-formed overrides and skips malformed ones`() {
        PinOverrides.cache = PinOverrides.parse(body("dir-a/encoder.onnx" to shaA))
        assertEquals(1, PinOverrides.cache?.size)
        val o = PinOverrides.cache!!.values.first()
        assertEquals("dir-a", o.dir)
        assertEquals("encoder.onnx", o.file)
        assertEquals(listOf(shaA), o.acceptableHashes)
        assertNull(o.fallbackUrl)
        PinOverrides.cache = null
    }

    @Test
    fun `parse rejects non-hex hashes and empty keys`() {
        val json = JSONArray()
            .put(JSONObject().put("dir", "d").put("file", "f")
                .put("acceptableHashes", JSONArray().put("nope")))
            .put(JSONObject().put("dir", "").put("file", "f")
                .put("acceptableHashes", JSONArray().put(shaA)))
            .put(JSONObject().put("dir", "d2").put("file", "")
                .put("acceptableHashes", JSONArray().put(shaA)))
        assertTrue(PinOverrides.parse(JSONObject().put("overrides", json).toString()).isEmpty())
    }

    @Test
    fun `verdict accepts only the published hash for that dir and file`() {
        PinOverrides.cache = PinOverrides.parse(body("d/f" to shaA))
        assertTrue(PinOverrides.verdictFor("d", "f", shaA))
        assertFalse(PinOverrides.verdictFor("d", "f", shaB))
        assertFalse(PinOverrides.verdictFor("d", "other", shaA))
        assertFalse(PinOverrides.verdictFor("other", "f", shaA))
        PinOverrides.cache = null
        assertFalse("no cache = no verdict (the not-wired headless behavior)",
            PinOverrides.verdictFor("d", "f", shaA))
    }

    @Test
    fun `fallbackFor serves the listed file only`() {
        PinOverrides.cache = PinOverrides.parse(
            org.json.JSONObject().put(
                "overrides",
                JSONArray()
                    .put(entry("d/f", shaA).put("fallbackUrl", "https://mirror.example/d/f"))
                    .put(entry("d/other", shaB)),
            ).toString(),
        )
        assertEquals("https://mirror.example/d/f", PinOverrides.fallbackFor("d", "f"))
        assertNull(PinOverrides.fallbackFor("d", "other"))
        assertNull(PinOverrides.fallbackFor("x", "f"))
        PinOverrides.cache = null
    }

    @Test
    fun `heal store round trips and overlays the catalog pins`() {
        val dir = tmp.newFolder("store")
        PinHeals.init(dir)
        assertNull(PinHeals.healed("d", "f"))
        PinHeals.recordHeal("d", "f", shaA)
        assertEquals(shaA, PinHeals.healed("d", "f"))
        // Re-init from the same dir: the heal PERSISTS (process restart).
        PinHeals.init(dir)
        assertEquals(shaA, PinHeals.healed("d", "f"))
        // The load-time overlay: healed REPLACES catalog, others untouched.
        val overlay = PinHeals.overlay("d", mapOf("f" to shaB, "g" to shaC))
        assertEquals("healed replaces catalog", shaA, overlay["f"])
        assertEquals("untouched files pass through", shaC, overlay["g"])
        // A different dir does not inherit the heal.
        assertEquals(shaB, PinHeals.overlay("other", mapOf("f" to shaB))["f"])
    }

    @Test
    fun `fetch success feeds verdicts and is cache-backed`() = runTest {
        var fetches = 0
        PinOverrides.fetcher = { fetches++; body("d/f" to shaA) }
        PinOverrides.cache = null
        PinOverrides.onDemand()
        assertEquals(1, fetches)
        assertTrue(PinOverrides.verdictFor("d", "f", shaA))
        PinOverrides.onDemand()
        assertEquals("the cache backs repeat consultations", 1, fetches)
    }

    @Test
    fun `fetch failure degrades to no channel without refetch loops`() = runTest {
        var fetches = 0
        PinOverrides.fetcher = { fetches++; null }
        PinOverrides.cache = null
        PinOverrides.onDemand()
        assertEquals(1, fetches)
        assertFalse("an unfetchable channel yields no verdict", PinOverrides.verdictFor("d", "f", shaA))
        PinOverrides.onDemand()
        assertEquals("no refetch loop on the degraded channel", 1, fetches)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun isolate() {
        // PinHeals is process-wide: every test roots it at a fresh empty
        // store so test order cannot change verdicts (the not-wired and
        // fetch tests must see NO heals).
        PinHeals.resetForTest(tmp.newFolder("heals"))
    }
}
