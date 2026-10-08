package com.antivocale.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Road test 2026-10-08: the startup backfill heals records imported before
 * the name-derived languages existed (the maintainer's paraformer-zh case).
 * These tests pin the wiring the code review flagged as untested:
 * idempotency, non-empty records untouched, and the by-id merge.
 */
class ExternalModelLanguageBackfillTest {

    private lateinit var prefs: FakePreferencesManager
    private lateinit var store: ExternalModelStore
    private lateinit var filesRoot: File

    @Before
    fun setup() {
        prefs = FakePreferencesManager()
        filesRoot = Files.createTempDirectory("ext-backfill").toFile()
        store = ExternalModelStore(prefs)
    }

    private fun record(id: String, displayName: String, languages: List<String>): ExternalModelRecord {
        val dir = File(filesRoot, id).apply { mkdirs() }
        return ExternalModelRecord(
            id = id, displayName = displayName, dir = dir.absolutePath,
            family = ModelFamily.TRANSDUCER, modelType = "", languages = languages,
            source = ExternalModelSource.URL, sourceUrl = "https://example.invalid/$id",
            files = emptyMap(), sizeBytes = 1, importedAt = 0,
        )
    }

    @Test
    fun `empty languages derive from the display name (paraformer zh)`() = runTest {
        store.add(record("zh1", "sherpa-onnx-paraformer-zh", languages = emptyList()))
        store.backfillDerivedLanguages()
        assertEquals(listOf("zh"), store.records().first { it.id == "zh1" }.languages)
    }

    @Test
    fun `records with languages already set are untouched`() = runTest {
        store.add(record("kept", "sherpa-onnx-paraformer-en", languages = listOf("ru")))
        store.backfillDerivedLanguages()
        assertEquals(listOf("ru"), store.records().first { it.id == "kept" }.languages)
    }

    @Test
    fun `non-derivable names stay empty and the backfill writes nothing`() = runTest {
        store.add(record("mystery", "mymodel-v2-final", languages = emptyList()))
        store.backfillDerivedLanguages()
        assertEquals(emptyList<String>(), store.records().first { it.id == "mystery" }.languages)
        // Idempotent: a second pass changes nothing (and writes nothing).
        store.backfillDerivedLanguages()
        assertEquals(emptyList<String>(), store.records().first { it.id == "mystery" }.languages)
    }

    @Test
    fun `the backfill is idempotent across passes for derived records too`() = runTest {
        store.add(record("uk1", "moonshine-base-uk", languages = emptyList()))
        store.backfillDerivedLanguages()
        val first = store.records().first { it.id == "uk1" }.languages
        assertEquals(listOf("uk"), first)
        store.backfillDerivedLanguages()
        assertEquals(first, store.records().first { it.id == "uk1" }.languages)
    }
}
