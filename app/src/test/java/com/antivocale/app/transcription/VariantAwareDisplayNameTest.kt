package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-436 derivation matrix for [variantAwareDisplayName]: the ONE name both
 * the GH #45 log-row write and ActiveModelRepository.modelName surface. Every
 * input path is a real catalog dir name; every expected output is the
 * default-locale value from values/strings.xml, which the context stub maps
 * resource by resource.
 */
class VariantAwareDisplayNameTest {

    private val registry = staticRegistry()

    /** getString stubbed with the default-locale values of the real resources. */
    private fun context(): Context = mockk<Context>(relaxed = true) {
        every { getString(R.string.parakeet_name) } returns "Parakeet TDT"
        every { getString(R.string.parakeet_smoothquant_title) } returns "Parakeet TDT (SmoothQuant)"
        every { getString(R.string.parakeet_stock_title) } returns "Parakeet TDT (Stock int8)"
        every { getString(R.string.whisper_title) } returns "Whisper"
        every { getString(R.string.whisper_turbo_title) } returns "Whisper Turbo"
        every { getString(R.string.whisper_medium_title) } returns "Whisper Medium"
        every { getString(R.string.whisper_small_title) } returns "Whisper Small"
        every { getString(R.string.whisper_distil_large_v3_title) } returns "Distil Italian"
        every { getString(R.string.qwen3_asr_title) } returns "Qwen3-ASR (52 languages)"
        every { getString(R.string.nemotron_name) } returns "Nemotron 3.5"
        every { getString(R.string.gigaam_name) } returns "GigaAM v3"
        every { getString(R.string.llm_backend_name) } returns "Gemma (LiteRT-LM)"
    }

    @Test
    fun `whisper variants resolve their localized titles`() {
        val context = context()
        val descriptor = registry.byBackendId(BuiltInBackendIds.WHISPER)!!
        assertEquals(
            "Whisper Small",
            variantAwareDisplayName(context, descriptor, "/models/sherpa-onnx-whisper-small"))
        assertEquals(
            "Whisper Turbo",
            variantAwareDisplayName(context, descriptor, "/models/sherpa-onnx-whisper-turbo"))
        assertEquals(
            "Whisper Medium",
            variantAwareDisplayName(context, descriptor, "/models/sherpa-onnx-whisper-medium"))
    }

    @Test
    fun `variant title without the family name joins as family plus variant`() {
        // "Distil Italian" does not contain "Whisper": the join keeps the family
        // context in the Logs UI without duplicating it where the title already
        // carries the family name.
        val descriptor = registry.byBackendId(BuiltInBackendIds.WHISPER)!!
        assertEquals(
            "Whisper Distil Italian",
            variantAwareDisplayName(context(), descriptor, "/models/sherpa-onnx-whisper-distil-large-v3-it"))
    }

    @Test
    fun `parakeet variants resolve their quantization titles`() {
        val context = context()
        val descriptor = registry.byBackendId(BuiltInBackendIds.PARAKEET)!!
        assertEquals(
            "Parakeet TDT (SmoothQuant)",
            variantAwareDisplayName(context, descriptor, "/models/parakeet-tdt-0.6b-v3-smoothquant"))
        assertEquals(
            "Parakeet TDT (Stock int8)",
            variantAwareDisplayName(context, descriptor, "/models/parakeet-tdt-0.6b-v3-int8"))
    }

    @Test
    fun `single-variant entries keep the plain family label`() {
        val context = context()
        assertEquals(
            "Qwen3-ASR (52 languages)",
            variantAwareDisplayName(context, registry.byBackendId(BuiltInBackendIds.QWEN3_ASR)!!,
                "/models/sherpa-onnx-qwen3-asr-0.6b-int8"))
        assertEquals(
            "Nemotron 3.5",
            variantAwareDisplayName(context, registry.byBackendId(BuiltInBackendIds.NEMOTRON)!!,
                "/models/nemotron-3.5-asr-streaming-0.6b-1120ms-int8"))
        assertEquals(
            "GigaAM v3",
            variantAwareDisplayName(context, registry.byBackendId(BuiltInBackendIds.GIGAAM)!!,
                "/models/gigaam-v3"))
    }

    @Test
    fun `llm backend has no catalog entry and keeps its fixed label`() {
        assertEquals(
            "Gemma (LiteRT-LM)",
            variantAwareDisplayName(context(), registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)!!,
                "/models/gemma.task"))
    }

    @Test
    fun `blank null or unresolvable path keeps the plain family label`() {
        val context = context()
        val descriptor = registry.byBackendId(BuiltInBackendIds.WHISPER)!!
        assertEquals("Whisper", variantAwareDisplayName(context, descriptor, null))
        assertEquals("Whisper", variantAwareDisplayName(context, descriptor, ""))
        assertEquals("Whisper", variantAwareDisplayName(context, descriptor, "   "))
        // Not a catalog dir name: variantForDirName's default-variant fallback
        // must NOT label an unknown directory as the default variant.
        assertEquals("Whisper", variantAwareDisplayName(context, descriptor, "/models/whisper-test"))
    }

    @Test
    fun `external records keep the record display name`() = runTest {
        val fake = FakePreferencesManager()
        val store = ExternalModelStore(fake, dirExists = { true })
        val record = ExternalModelRecord(
            id = "a1b2c3d4e5f6", displayName = "GigaAM v3 custom", dir = "/x/gigaam-v3-custom",
            family = ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
            languages = listOf("ru"), source = ExternalModelSource.LOCAL, sourceUrl = null,
            files = mapOf("encoder.int8.onnx" to FilePin("00", verified = true)),
            sizeBytes = 1L, importedAt = 0L,
        )
        store.add(record)
        val provider = object : ExternalModelRecordsProvider {
            override val records = MutableStateFlow(listOf(record))
        }
        val registry = BackendRegistry(store, provider)

        assertEquals(
            "GigaAM v3 custom",
            variantAwareDisplayName(mockk(), registry.byBackendId("external:a1b2c3d4e5f6")!!,
                "/x/gigaam-v3-custom"))
    }
}
