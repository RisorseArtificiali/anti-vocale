package com.antivocale.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-785: the provider's manifest declaration, pinned as text from the
 * source manifest (the ManifestFgsMirrorTest / SubtitleMimeManifestTest
 * precedent: no merged-manifest machinery, the source file is the
 * contract). The service MUST ship exported (clients bind cross-app by the
 * action), disabled (invisible to provider sweeps until the user opts in),
 * and carry the contract action in its filter; a drift on any of the three
 * breaks discovery or the gate silently.
 */
class OpenTranscribeManifestContractTest {

    private val manifest: String = File("src/main/AndroidManifest.xml").readText()

    private fun serviceNode(): String {
        val idx = manifest.indexOf("android:name=\".service.OpenTranscribeProviderService\"")
        assertTrue("the provider service must be declared in the main manifest", idx >= 0)
        val nodeEnd = manifest.indexOf("</service>", idx)
        assertTrue("the provider service node must be closed", nodeEnd > 0)
        return manifest.substring(idx, nodeEnd)
    }

    private fun attribute(node: String, name: String): String? =
        Regex("android:$name=\"([^\"]+)\"").find(node)?.groupValues?.get(1)

    @Test
    fun `the contract action is declared in the service's intent filter`() {
        val node = serviceNode()
        assertNotNull("the intent-filter action is missing", node.indexOf("org.opentranscribe.api.ITranscriptionService"))
        assertTrue(
            "the action must sit inside an intent-filter",
            node.contains("<intent-filter>"),
        )
    }

    @Test
    fun `the service ships exported for cross-app binding`() {
        assertEquals("true", attribute(serviceNode(), "exported"))
    }

    @Test
    fun `the service ships disabled so the gate defaults to invisible`() {
        assertEquals("false", attribute(serviceNode(), "enabled"))
    }

    @Test
    fun `the foreground promotion carries the specialUse type like the InferenceService precedent`() {
        assertEquals("specialUse", attribute(serviceNode(), "foregroundServiceType"))
    }
}
