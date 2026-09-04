package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.FakePreferencesManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TDD red-phase test for [BackendRegistry] (TASK-254).
 *
 * Contract pinned by this test:
 *   data class BackendDescriptor(
 *       val backendId: String,
 *       val shareAlias: String,
 *       val isStreaming: Boolean,
 *       val displayNameResId: Int?,
 *       val deriveDisplayName: (context: Context, path: String) -> String,
 *       val modelPathFlow: (PreferencesManager) -> Flow<String?>,
 *       val saveModelPath: suspend (PreferencesManager, String) -> Unit,
 *       val clearModelPath: suspend (PreferencesManager) -> Unit,
 *   )
 *   @Singleton class BackendRegistry @Inject constructor(
 *       externalModelStore: ExternalModelStore,
 *       recordsProvider: ExternalModelRecordsProvider,
 *   ) {
 *       val backends: List<BackendDescriptor>
 *       fun byBackendId(backendId: String?): BackendDescriptor?
 *       fun byShareAlias(alias: String?): BackendDescriptor?
 *   }
 *
 * The old bookkeeping [ExtractionService.ModelType] enum is gone (consolidation):
 * sherpa-onnx dispatch keys on the backend-id string everywhere, so there is no
 * byModelType lookup to pin.
 *
 * Share aliases below are pinned against the manifest activity-alias literals
 * (the registry is the single source since TASK-323: ShareReceiverActivity
 * resolves aliases via byShareAlias, so this pins registry <-> manifest).
 */
class BackendRegistryTest {

    // Static-backend fixture: an empty external-model provider derives no dynamic
    // descriptors, so the static tests below pin exactly the static six.
    private val store = com.antivocale.app.data.ExternalModelStore(
        FakePreferencesManager(),
        dirExists = { true },
    )
    private val registry = BackendRegistry(store, emptyRecordsProvider())

    /** The six enabled backends, in canonical order (default backend first). */
    private val expectedIds = listOf(
        BuiltInBackendIds.PARAKEET,
        BuiltInBackendIds.WHISPER,
        BuiltInBackendIds.QWEN3_ASR,
        BuiltInBackendIds.NEMOTRON,
        BuiltInBackendIds.GIGAAM,
        LlmTranscriptionBackend.BACKEND_ID,
    )

    /** backendId -> the FakePreferencesManager backing flow its accessors must use. */
    private val expectedPrefFlows: Map<String, (FakePreferencesManager) -> MutableStateFlow<String?>> = mapOf(
        BuiltInBackendIds.PARAKEET to { it._sherpaModelPath(BuiltInBackendIds.PARAKEET) },
        BuiltInBackendIds.WHISPER to { it._sherpaModelPath(BuiltInBackendIds.WHISPER) },
        BuiltInBackendIds.QWEN3_ASR to { it._sherpaModelPath(BuiltInBackendIds.QWEN3_ASR) },
        BuiltInBackendIds.NEMOTRON to { it._sherpaModelPath(BuiltInBackendIds.NEMOTRON) },
        BuiltInBackendIds.GIGAAM to { it._sherpaModelPath(BuiltInBackendIds.GIGAAM) },
        LlmTranscriptionBackend.BACKEND_ID to FakePreferencesManager::_modelPath,
    )

    // The static descriptors are built from the bundled catalog (lazily on first
    // access), so every test that touches the registry needs it seeded.
    @org.junit.Before
    fun seedCatalog() = seedCatalogForTest()

    @Test
    fun `static six backend ids, dynamic externals counted separately`() {
        val ids = registry.backends.map { it.backendId }
        assertEquals(expectedIds.size, ids.size)
        assertEquals(expectedIds, ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `lookup by backendId round-trips to the registered descriptor`() {
        for (descriptor in registry.backends) {
            val found = registry.byBackendId(descriptor.backendId)
            assertEquals("byBackendId(${descriptor.backendId}) must return the registered descriptor", descriptor, found)
        }
    }

    @Test
    fun `lookup by shareAlias round-trips to the registered descriptor`() {
        for (descriptor in registry.backends) {
            val found = registry.byShareAlias(descriptor.shareAlias)
            assertEquals("byShareAlias(${descriptor.shareAlias}) must return the registered descriptor", descriptor, found)
        }
    }

    @Test
    fun `share aliases are the ShareReceiverActivity ALIAS values and are unique`() {
        val expectedAliases = setOf(
            "com.antivocale.app.ShareParakeet",
            "com.antivocale.app.ShareWhisper",
            "com.antivocale.app.ShareQwen3",
            "com.antivocale.app.ShareNemotron",
            "com.antivocale.app.ShareGigaam",
            "com.antivocale.app.ShareGemma",
        )
        val aliases = registry.backends.map { it.shareAlias }
        assertEquals(expectedAliases, aliases.toSet())
        assertEquals(aliases.size, aliases.toSet().size)
    }

    @Test
    fun `unknown or null identifiers return null`() {
        assertNull(registry.byBackendId("no-such-backend"))
        assertNull(registry.byBackendId(null))
        assertNull(registry.byShareAlias("com.antivocale.app.ShareNoSuch"))
        assertNull(registry.byShareAlias(null))
        assertNull("no static backend carries the blank alias anymore", registry.byShareAlias(""))
    }

    @Test
    fun `descriptor identifiers are mutually consistent`() {
        // The two identifier schemes must agree: looking up by one key and
        // re-reading the other yields the same pair everywhere.
        for (descriptor in registry.backends) {
            assertEquals(descriptor.backendId, registry.byShareAlias(descriptor.shareAlias)?.backendId)
        }
    }

    @Test
    fun `preference accessors delegate to the per-backend PreferencesManager members`() = runTest {
        for ((backendId, expectedFlow) in expectedPrefFlows) {
            val fake = FakePreferencesManager()
            val descriptor = registry.byBackendId(backendId)!!

            val sentinel = "/models/$backendId"
            descriptor.saveModelPath(fake, sentinel)
            assertEquals("$backendId save must hit its own preference", sentinel, expectedFlow(fake).value)

            // No cross-talk: every other backend's model-path flow is still null.
            for (other in expectedPrefFlows.values) {
                if (other != expectedFlow) {
                    assertNull("$backendId save must not touch other preferences", other(fake).value)
                }
            }

            assertEquals(sentinel, descriptor.modelPathFlow(fake).first())

            descriptor.clearModelPath(fake)
            assertNull("$backendId clear must reset its own preference", expectedFlow(fake).value)
        }
    }

    @Test
    fun `all static backends expose dedicated display-name resources`() {
        assertEquals(R.string.parakeet_name, registry.byBackendId(BuiltInBackendIds.PARAKEET)?.displayNameResId)
        assertEquals(R.string.whisper_title, registry.byBackendId(BuiltInBackendIds.WHISPER)?.displayNameResId)
        assertEquals(R.string.qwen3_asr_title, registry.byBackendId(BuiltInBackendIds.QWEN3_ASR)?.displayNameResId)
        assertEquals(R.string.nemotron_name, registry.byBackendId(BuiltInBackendIds.NEMOTRON)?.displayNameResId)
        assertEquals(R.string.gigaam_name, registry.byBackendId(BuiltInBackendIds.GIGAAM)?.displayNameResId)
        assertEquals(R.string.llm_backend_name, registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)?.displayNameResId)
    }

    @Test
    fun `default display-name derivation falls back to the model file name`() {
        val context = mockk<Context>()
        val descriptor = registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)!!
        assertEquals("gemma-2b-it.task", descriptor.deriveDisplayName(context, "/data/models/gemma-2b-it.task"))
    }

    @Test
    fun `qwen3 display-name derivation resolves the variant title via the catalog`() {
        val context = mockk<Context>()
        every { context.getString(R.string.qwen3_asr_0_6b_title) } returns "Qwen3-ASR 0.6B"
        val descriptor = registry.byBackendId(BuiltInBackendIds.QWEN3_ASR)!!

        // Directory name carries the 0.6b marker the catalog variant detects.
        assertEquals(
            "Qwen3-ASR 0.6B",
            descriptor.deriveDisplayName(context, "/data/models/qwen3-asr-0.6b"),
        )
        // Unrecognized directory names fall back to the file name.
        assertEquals(
            "qwen3-asr-unknown",
            descriptor.deriveDisplayName(context, "/data/models/qwen3-asr-unknown"),
        )
    }

    @Test
    fun `whisper display-name derivation falls back to the file name for an unrecognized directory`() {
        val context = mockk<Context>(relaxed = true)
        val descriptor = registry.byBackendId(BuiltInBackendIds.WHISPER)!!
        // Not a real model directory on disk, so validateModelDirectory returns null.
        assertEquals(
            File("/models/whisper-foreign-dir").name,
            descriptor.deriveDisplayName(context, "/models/whisper-foreign-dir"),
        )
    }

    @Test
    fun `only nemotron is a streaming backend`() {
        val streaming = registry.backends.filter { it.isStreaming }.map { it.backendId }
        assertEquals(listOf(BuiltInBackendIds.NEMOTRON), streaming)
    }

    @Test
    fun `rtfEstimate parakeet exception, conservative defaults elsewhere`() {
        // TASK-432: cold-start dialog estimate fallback (calibrator samples win after 2 runs)
        assertEquals(15f, registry.byBackendId(BuiltInBackendIds.PARAKEET)!!.rtfEstimate)
        for (descriptor in registry.backends) {
            assertTrue(
                "rtfEstimate of ${descriptor.backendId} must stay within the conservative band",
                descriptor.rtfEstimate in 1f..15f)
        }
        assertEquals(4f, registry.byBackendId(BuiltInBackendIds.WHISPER)!!.rtfEstimate)
        assertEquals(1f, registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)!!.rtfEstimate)
    }

    @Test
    fun `external descriptors get the conservative cluster rtfEstimate`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))
        assertEquals(4f, registry.byBackendId("external:a1b2c3d4e5f6")!!.rtfEstimate)
    }

    // ---- dynamic external descriptors (spec v2a) ----

    private fun externalRecord(id: String = "a1b2c3d4e5f6") = com.antivocale.app.data.ExternalModelRecord(
        id = id, displayName = "GigaAM v3", dir = "/x/gigaam-v3-$id",
        family = com.antivocale.app.data.ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
        languages = listOf("ru"), source = com.antivocale.app.data.ExternalModelSource.LOCAL, sourceUrl = null,
        files = mapOf("encoder.int8.onnx" to com.antivocale.app.data.FilePin("00", verified = true)),
        sizeBytes = 1L, importedAt = 0L,
    )

    private fun providerWith(vararg records: com.antivocale.app.data.ExternalModelRecord): com.antivocale.app.data.ExternalModelRecordsProvider =
        object : com.antivocale.app.data.ExternalModelRecordsProvider {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(records.toList())
        }

    @Test
    fun `external records derive descriptors with no share alias`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))

        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")
        assertNotNull(descriptor)
        assertEquals("", descriptor!!.shareAlias)
        assertEquals("GigaAM v3", descriptor.deriveDisplayName(mockk(), "/anywhere"))
    }

    @Test
    fun `provider with no records derives nothing`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { false })
        store.add(externalRecord())
        // Empty provider: the real provider filters invalid records out, so nothing derives.
        val registry = BackendRegistry(store, emptyRecordsProvider())
        assertNull(registry.byBackendId("external:a1b2c3d4e5f6"))
    }

    @Test
    fun `static six plus N external backends coexist and stay unique`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord("111111111111")); store.add(externalRecord("222222222222"))
        val registry = BackendRegistry(store, providerWith(externalRecord("111111111111"), externalRecord("222222222222")))
        val ids = registry.backends.map { it.backendId }
        assertEquals(expectedIds.size + 2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(expectedIds, ids.take(expectedIds.size))  // static first, canonical order preserved
    }

    @Test
    fun `model-path accessors delegate to the store record`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))
        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")!!
        descriptor.saveModelPath(fake, "/new/dir")
        // Store records are keyed by identity, not a path preference: saving redirects the record's dir.
        assertEquals("/new/dir", store.records().first().dir)
    }
    @Test
    fun `punctuation flag is false only for gigaam`() {
        // TASK-276: the AUTO mode of the punctuation pass keys on this flag.
        val byId = registry.backends.associateBy { it.backendId }
        assertFalse("gigaam must be flagged non-punctuating",
            byId.getValue(BuiltInBackendIds.GIGAAM).punctuatesOutput)
        for (id in BuiltInBackendIds.ALL.filter { it != BuiltInBackendIds.GIGAAM }) {
            assertTrue("$id must keep the punctuating default", byId.getValue(id).punctuatesOutput)
        }
        assertTrue("llm must keep the punctuating default",
            byId.getValue(LlmTranscriptionBackend.BACKEND_ID).punctuatesOutput)
    }

}
