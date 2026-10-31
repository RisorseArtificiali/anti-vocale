package com.antivocale.app.receiver

import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.emptyRecordsProvider
import com.antivocale.app.transcription.seedCatalogForTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShareReceiverActivityAliasTest {

    /** Registry with no external records: the static alias set only. */
    private val registry = BackendRegistry(
        ExternalModelStore(FakePreferencesManager(), dirExists = { true }),
        emptyRecordsProvider(),
    )

    // Static descriptors read the bundled catalog lazily; seed it before use.
    @Before
    fun seedCatalog() = seedCatalogForTest()

    @Test
    fun `parakeet alias maps to sherpa-onnx backend`() {
        assertEquals("sherpa-onnx", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareParakeet", registry))
    }

    @Test
    fun `whisper alias maps to whisper backend`() {
        assertEquals("whisper", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareWhisper", registry))
    }

    @Test
    fun `gemma alias maps to llm backend`() {
        assertEquals("llm", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareGemma", registry))
    }

    @Test
    fun `default activity class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.receiver.ShareReceiverActivity", registry))
    }

    @Test
    fun `unknown class returns null`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("com.antivocale.app.UnknownActivity", registry))
    }

    @Test
    fun `qwen3 alias maps to qwen3-asr backend`() {
        assertEquals("qwen3-asr", ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareQwen3", registry))
    }

    @Test
    fun `nemotron alias maps to nemotron-streaming backend`() {
        assertEquals(
            "nemotron-streaming",
            ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareNemotron", registry)
        )
    }

    @Test
    fun `empty string returns null (no static backend carries the blank alias)`() {
        assertNull(ShareReceiverActivity.backendIdForAlias("", registry))
    }

    @Test
    fun `share external family alias resolves to the sentinel`() {
        assertEquals(
            "external",
            ShareReceiverActivity.backendIdForAlias("com.antivocale.app.ShareExternal", registry)
        )
    }

    /**
     * TASK-927 review V4: the manifest cannot reference constants, so this
     * test IS the sync. Every SEND filter on a ShareReceiverActivity target
     * (main activity and share aliases) must also declare
     * ACTION_SEND_MULTIPLE, or a multi share never offers the pinned backend
     * targets and an explicit multi send to an alias dead-ends.
     */
    @Test
    fun `every ShareReceiverActivity SEND filter also declares SEND_MULTIPLE`() {
        val manifest = com.antivocale.app.testing.RepoFiles.readText("src/main/AndroidManifest.xml")
        // Only filters inside ShareReceiverActivity-targeted blocks count;
        // other components must not be constrained by this pin.
        val activityBlocks = Regex(
            "<activity(-alias)?\\b.*?</activity(-alias)?>",
            RegexOption.DOT_MATCHES_ALL,
        )
            .findAll(manifest)
            .filter { "ShareReceiverActivity" in it.value }
        val scopedSendFilters = activityBlocks.flatMap { block ->
            Regex(
                "<intent-filter>.*?</intent-filter>",
                RegexOption.DOT_MATCHES_ALL,
            )
                .findAll(block.value)
                .filter { "android.intent.action.SEND\"" in it.value }
        }.toList()

        assertTrue("expected share SEND filters in the manifest", scopedSendFilters.isNotEmpty())
        scopedSendFilters.forEach { filter ->
            assertTrue(
                "SEND filter missing ACTION_SEND_MULTIPLE:\n${filter.value}",
                "android.intent.action.SEND_MULTIPLE" in filter.value,
            )
        }
    }
}
