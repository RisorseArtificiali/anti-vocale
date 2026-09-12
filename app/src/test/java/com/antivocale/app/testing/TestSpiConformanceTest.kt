package com.antivocale.app.testing

import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.PreferencesManager
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-469: the structural drift guards. The set-side dispatch is now a
 * single map (SET_KEYS derives from it), so the remaining drift surfaces are
 * the DOC table, the get() field list, and the fake's write behavior; each
 * gets one conformance test here.
 */
class TestSpiConformanceTest {

    private fun ops(fake: FakePreferencesManager) =
        TestSpiOps(fake, ExternalModelStore(fake))

    private fun repoRoot(): File =
        listOf(".", "..", "../..").firstOrNull { File(it, "docs/testing-spi.md").isFile }
            ?.let { File(it) }
            ?: error("docs/testing-spi.md not found from " + File(".").absolutePath)

    /** The doc's Set-keys table must name exactly the dispatch map's keys:
     *  a key added to the SPI without a doc row (or a doc row for a key the
     *  SPI dropped) fails here. */
    @Test
    fun `doc set-keys table matches the dispatch map`() {
        val setKeys = ops(FakePreferencesManager()).SET_KEYS
        val docFile = File(repoRoot(), "docs/testing-spi.md")
        val doc = docFile.readText()
        // Scoped to the key table section so a same-shaped table elsewhere in
        // the doc cannot inject phantom keys; digits allowed (qwen3-style).
        val table = Regex("## Action and extras([\\s\\S]*?)\\n## ").find(doc)?.groupValues?.get(1)
            ?: error("key table section not found in " + docFile.absolutePath)
        val docKeys = Regex("^\\| `([a-z0-9_]+)` \\| `save", RegexOption.MULTILINE).findAll(table)
            .map { it.groupValues[1] }.toList()
        assertTrue(
            "no key rows in " + docFile.absolutePath + " (" + doc.length.toString() + " chars, cwd=" +
                File(".").absolutePath + ")",
            docKeys.isNotEmpty())
        assertEquals(
            "docs/testing-spi.md set-keys table and SET_KEYS drifted",
            setKeys.sorted(),
            (docKeys).sorted(),
        )
    }

    /** Every Flow getter on [PreferencesManager] must surface in op=get
     *  (top-level or via an explicit exclusion), and every get key must map
     *  back: the same drift class that motivated the set-side tables. */
    @Test
    fun `get exposes every preference flow and nothing invented`() = runTest {
        val fake = FakePreferencesManager()
        val json = org.json.JSONObject(ops(fake).handle(TestSpiOps.OP_GET))
        val names = json.names() ?: org.json.JSONArray()
        val getKeys = (0 until names.length()).map { names.getString(it) }
            .filter { it !in setOf("op", "paths") }

        // Flows deliberately NOT in get: partial-transcription resume state
        // (write-path only), benchmark history, the retired GGUF backend's
        // state, the one-shot external-migration marker, and the path flows
        // that render inside "paths"/"activeModelPath" instead.
        val excluded = setOf(
            "partialTranscriptionText", "partialTranscriptionTimestamp",
            "allBenchmarkResults",
            "customTransducerModelPath", "customTransducerModelType", "ggufModelPath",
            "externalMigrationDone",
            "modelPath", // rendered as activeModelPath/paths.llm
            "externalModelsJson", // op=records covers it
        )
        val flowProps = PreferencesManager::class.java.methods
            .filter { Flow::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0 }
            .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercaseChar() } }
            .filterNot { it in excluded }

        val expectedGetKeys = flowProps.map { prop ->
            // The renames get() carries; every other flow keeps its name.
            when (prop) {
                "progressiveTranscription" -> "progressiveEnabled"
                "keepAliveTimeout" -> "keepAliveTimeoutMinutes"
                else -> prop
            }
        }.toSet() + "activeModelPath"

        assertEquals(
            "get() fields and the PreferencesManager flows drifted",
            expectedGetKeys.sorted(),
            getKeys.sorted(),
        )
    }

    /** The live-found fake drift class: mutators must apply the same
     *  transformation as the impl (the 500-char prompt cap, now one shared
     *  constant). */
    @Test
    fun `fake prompt mutators cap at the shared constant`() = runTest {
        val fake = FakePreferencesManager()
        val long = "x".repeat(PreferencesManager.PROMPT_CAP + 100)
        fake.saveSummaryPrompt(long)
        fake.savePunctuationPrompt(long)
        fake.saveDefaultPrompt(long)
        assertEquals(PreferencesManager.PROMPT_CAP, fake.summaryPrompt.first().length)
        assertEquals(PreferencesManager.PROMPT_CAP, fake.punctuationPrompt.first().length)
        assertEquals(PreferencesManager.PROMPT_CAP, fake.defaultPrompt.first().length)
    }
}
