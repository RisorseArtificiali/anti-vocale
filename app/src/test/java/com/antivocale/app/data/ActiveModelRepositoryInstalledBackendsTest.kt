package com.antivocale.app.data

import com.antivocale.app.transcription.BuiltInBackendIds
import com.antivocale.app.transcription.staticRegistry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-740.1: the installed-backends derivation pins (the refine-model
 * picker's source): the blank-path filter, the LLM's conditional entry,
 * and the external-records arm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveModelRepositoryInstalledBackendsTest {

    private fun repo(
        preferences: PreferencesManager,
        records: List<ExternalModelRecord> = emptyList(),
    ): ActiveModelRepository {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = object : ExternalModelRecordsProvider {
            override val records = MutableStateFlow(records)
        }
        val registry = com.antivocale.app.transcription.staticRegistry()
        return ActiveModelRepository(preferences, context, registry, provider)
    }

    @Test
    fun `blank paths exclude, non-blank include, and the LLM rides its model path`() = runTest {
        val fake = FakePreferencesManager()
        fake._sherpaModelPath("whisper").value = "/models/whisper-turbo"
        fake._modelPath.value = "/models/gemma.litertlm"
        val flow = repo(fake).installedBackendsFlow

        val ids = flow.first().map { it.backendId }
        assertTrue("whisper in: $ids", BuiltInBackendIds.WHISPER in ids)
        assertTrue("llm in: $ids", "llm" in ids)

        // blank the LLM path: the entry disappears
        fake._modelPath.value = ""
        val withoutLlm = flow.first().map { it.backendId }
        assertTrue("llm out: $withoutLlm", "llm" !in withoutLlm)
        // a blank catalog path excludes the entry
        fake._sherpaModelPath("whisper").value = ""
        val withoutWhisper = flow.first().map { it.backendId }
        assertTrue("whisper out: $withoutWhisper", BuiltInBackendIds.WHISPER !in withoutWhisper)
    }

    @Test
    fun `external records map to their record display names`() = runTest {
        val fake = FakePreferencesManager()
        val rec = record(displayName = "My Hebrew Whisper")
        val installed = repo(fake, listOf(rec)).installedBackendsFlow.first()
        val entry = installed.firstOrNull { it.backendId == "external:abc123def456" }
        assertEquals("My Hebrew Whisper", entry?.displayName)
    }
}

private fun record(displayName: String) = ExternalModelRecord(
    id = "abc123def456",
    displayName = displayName,
    dir = "/x/external",
    family = ModelFamily.WHISPER,
    modelType = "",
    languages = listOf("he"),
    source = ExternalModelSource.LOCAL,
    sourceUrl = null,
    files = emptyMap(),
    sizeBytes = 1L,
    importedAt = 1L,
)
