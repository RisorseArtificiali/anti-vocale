package com.antivocale.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-726/269: the lint-overlay re-assertions must mirror main. Once a
 * manifest overlay declares any <service>, lint resolves startForeground
 * call sites against that overlay, so the debug build-type overlay and the
 * playStore flavor manifest each re-assert the two real FGS declarations
 * with tools:replace (the value wins the merge). That override is silent:
 * if main ever changes a foregroundServiceType, a stale overlay would ship
 * a narrowed type and throw SecurityException at startForeground on
 * API 34+ in exactly the builds CI's lint gate (fdroidDebug, no flavor
 * manifest) never sees. This test IS the sync fence (the
 * SubtitleMimeManifestTest precedent): every overlay assertion must equal
 * main's live declaration, read as text from the three source manifests.
 */
class ManifestFgsMirrorTest {

    private val services = listOf(".service.InferenceService", ".service.ExtractionService")
    private val overlays = listOf(
        "debug build-type overlay" to "src/debug/AndroidManifest.xml",
        "playStore flavor manifest" to "src/playStore/AndroidManifest.xml",
    )

    /** Extracts the foregroundServiceType of one <service> node from raw manifest text. */
    private fun fgsType(manifest: String, service: String): String? {
        val idx = manifest.indexOf("android:name=\"$service\"")
        if (idx < 0) return null
        val nodeEnd = manifest.indexOf("</service>", idx).let { if (it < 0) idx + 400 else it }
        val node = manifest.substring(idx, minOf(nodeEnd, manifest.length))
        return Regex("android:foregroundServiceType=\"([^\"]+)\"").find(node)?.groupValues?.get(1)
    }

    @Test
    fun `every overlay re-assertion carries main's exact foregroundServiceType`() {
        val main = java.io.File("src/main/AndroidManifest.xml").readText()
        services.forEach { service ->
            val mainType = fgsType(main, service)
            assertTrue("main must declare a foregroundServiceType for $service", !mainType.isNullOrBlank())
            overlays.forEach { (label, path) ->
                val overlay = java.io.File(path).readText()
                assertEquals("$label drifted from main for $service", mainType, fgsType(overlay, service))
            }
        }
    }

    @Test
    fun `the overlay re-assertions win the merge instead of unioning`() {
        // tools:replace on the attribute is load-bearing: plain
        // tools:node=merge UNIONS multi-value attributes and shipped a
        // duplicated "specialUse|specialUse" token (found in the merged
        // artifact by review, 2026-09-30). Pin the selector's presence.
        overlays.forEach { (_, path) ->
            val text = java.io.File(path).readText()
            services.forEach { service ->
                val idx = text.indexOf("android:name=\"$service\"")
                assertTrue("$service re-assertion missing in $path", idx >= 0)
                val node = text.substring(idx, minOf(idx + 400, text.length))
                assertTrue(
                    "$service in $path must carry tools:replace=\"android:foregroundServiceType\"",
                    Regex("tools:replace=\"android:foregroundServiceType\"").containsMatchIn(node),
                )
            }
        }
    }
}
